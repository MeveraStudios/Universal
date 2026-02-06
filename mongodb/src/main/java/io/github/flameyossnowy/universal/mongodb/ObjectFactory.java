package io.github.flameyossnowy.universal.mongodb;

import io.github.flameyossnowy.universal.api.RepositoryRegistry;
import io.github.flameyossnowy.universal.api.annotations.OneToOne;
import io.github.flameyossnowy.universal.api.exceptions.ConstructorThrewException;
import io.github.flameyossnowy.universal.api.options.Query;
import io.github.flameyossnowy.universal.api.options.SelectQuery;
import io.github.flameyossnowy.universal.api.reflect.FieldData;
import io.github.flameyossnowy.universal.api.reflect.RepositoryInformation;
import io.github.flameyossnowy.universal.api.reflect.RepositoryMetadata;
import io.github.flameyossnowy.universal.api.resolver.TypeResolverRegistry;
import io.github.flameyossnowy.universal.mongodb.codec.MongoTypeCodec;
import io.github.flameyossnowy.universal.mongodb.result.MongoDatabaseResult;
import org.bson.Document;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.*;

@ApiStatus.Internal
@SuppressWarnings("unchecked")
public class ObjectFactory<T, ID> {

    private final RepositoryInformation repoInfo;
    private final TypeResolverRegistry typeResolverRegistry;
    private final boolean isRecord;
    private final RecordComponent[] recordComponents;

    // Thread-local loading context to prevent infinite recursion
    private static final ThreadLocal<Set<LoadingKey>> LOADING_CONTEXT =
        ThreadLocal.withInitial(HashSet::new);

    private record LoadingKey(Class<?> entityType, Object entityId) {}

    @SuppressWarnings("unused")
    public ObjectFactory(
        @NotNull RepositoryInformation repoInfo,
        @NotNull TypeResolverRegistry typeResolverRegistry,
        Class<T> type,
        Class<ID> idClass
    ) {
        this.repoInfo = repoInfo;
        this.typeResolverRegistry = typeResolverRegistry;
        this.isRecord = repoInfo.isRecord();
        this.recordComponents = isRecord ? repoInfo.getRecordComponents() : null;

        if (isRecord) {
            for (FieldData<?> field : repoInfo.getFields()) {
                if (field.oneToOne() != null || field.oneToMany() != null || field.manyToOne() != null) {
                    throw new UnsupportedOperationException("Records cannot contain relationships like oneToOne, oneToMany, or manyToOne: " + field.name());
                }
            }
        }
    }

    public Document toDocument(Object entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Cannot convert null entity to Document");
        }

        Document doc = new Document();

        for (FieldData<?> field : repoInfo.getFields()) {
            // Skip OneToMany - they don't get stored in the parent document
            if (field.oneToMany() != null) continue;

            MongoTypeCodec.setCurrentFieldName(field.name());
            try {
                Object value;

                // For ManyToOne, store the ID value in the field name itself
                if (field.manyToOne() != null) {
                    Object relatedEntity = field.getValue(entity);
                    if (relatedEntity != null) {
                        RepositoryInformation relatedInfo = RepositoryMetadata.getMetadata(field.type());
                        if (relatedInfo != null && relatedInfo.getPrimaryKey() != null) {
                            // Store the ID value using the field name directly
                            Object foreignKeyValue = relatedInfo.getPrimaryKey().getValue(relatedEntity);
                            doc.put(field.name(), foreignKeyValue);
                        }
                    }
                    continue;
                }

                // For OneToOne owning side, store the ID value in the field name itself
                if (field.oneToOne() != null) {
                    OneToOne oneToOne = field.oneToOne();
                    // Only owning side (has join column, no mappedBy) stores the FK
                    if (oneToOne.mappedBy().isEmpty()) {
                        Object relatedEntity = field.getValue(entity);
                        if (relatedEntity != null) {
                            RepositoryInformation relatedInfo = RepositoryMetadata.getMetadata(field.type());
                            if (relatedInfo != null && relatedInfo.getPrimaryKey() != null) {
                                // Store the ID value using the field name directly
                                Object foreignKeyValue = relatedInfo.getPrimaryKey().getValue(relatedEntity);
                                doc.put(field.name(), foreignKeyValue);
                            }
                        }
                    }
                    // Inverse side (has mappedBy) doesn't store anything
                    continue;
                }

                value = field.getValue(entity);

                // Store primary key as "_id" in MongoDB for proper indexing
                if (field.primary()) {
                    doc.put("_id", value);
                    if (!field.name().equals("_id")) {
                        doc.put(field.name(), value);
                    }
                } else {
                    doc.put(field.name(), value);
                }
            } finally {
                MongoTypeCodec.setCurrentFieldName(null);
            }
        }

        return doc;
    }

    @Nullable
    public T fromDocument(@Nullable Document doc) {
        if (doc == null) {
            return null;
        }

        if (isRecord) {
            return fromDocumentRecord(doc);
        }

        return fromDocumentClass(doc);
    }

    private @NotNull T fromDocumentRecord(@NotNull Document doc) {
        int length = recordComponents.length;
        Object[] args = new Object[length];

        for (int index = 0; index < length; index++) {
            RecordComponent rc = recordComponents[index];
            String fieldName = rc.getName();

            MongoTypeCodec.setCurrentFieldName(fieldName);
            try {
                Object value = doc.get(fieldName);
                if (value == null && isPrimaryKeyField(fieldName)) {
                    value = doc.get("_id");
                }

                FieldData<?> field = repoInfo.getField(fieldName);
                args[index] = field != null ? coerceValue(field, doc, value) : value;
            } finally {
                MongoTypeCodec.setCurrentFieldName(null);
            }
        }

        try {
            return (T) repoInfo.getRecordConstructor().newInstance(args);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException("Failed to instantiate record: " + repoInfo.getType().getName(), e);
        } catch (InvocationTargetException e) {
            throw new ConstructorThrewException("Record constructor threw exception: " + e.getMessage());
        }
    }

    private @NotNull T fromDocumentClass(@NotNull Document doc) {
        T entity = (T) repoInfo.newInstance();
        ID entityId = null;

        // First pass: non-relationship fields
        for (FieldData<?> field : repoInfo.getFields()) {
            if (field.manyToOne() != null || field.oneToOne() != null || field.oneToMany() != null) {
                continue;
            }

            Object value = doc.get(field.name());
            if (value == null && field.primary()) {
                value = doc.get("_id");
            }

            value = coerceValue(field, doc, value);

            if (field.primary()) {
                entityId = (ID) value;
            }

            if (value != null) {
                field.setValue(entity, value);
            }
        }

        LoadingKey loadingKey = new LoadingKey(repoInfo.getType(), entityId);
        Set<LoadingKey> loadingContext = LOADING_CONTEXT.get();
        boolean added = loadingContext.add(loadingKey);

        try {
            for (FieldData<?> field : repoInfo.getFields()) {
                if (field.manyToOne() != null) {
                    loadManyToOne(field, entity, doc, loadingContext);
                } else if (field.oneToOne() != null) {
                    loadOneToOne(field, entity, doc, entityId, loadingContext);
                } else if (field.oneToMany() != null) {
                    loadOneToMany(field, entity, entityId);
                }
            }
        } finally {
            if (added) {
                loadingContext.remove(loadingKey);
                if (loadingContext.isEmpty()) {
                    LOADING_CONTEXT.remove();
                }
            }
        }

        return entity;
    }

    @Contract("_, _, null -> null")
    private @Nullable Object coerceValue(@NotNull FieldData<?> field, @NotNull Document doc, @Nullable Object value) {
        if (value == null) return null;

        Class<?> targetType = field.type();
        if (targetType.isInstance(value)) {
            return value;
        }

        if (targetType.isPrimitive()) {
            switch (value) {
                case Number n when targetType == int.class -> {
                    return n.intValue();
                }
                case Number n when targetType == long.class -> {
                    return n.longValue();
                }
                case Number n when targetType == double.class -> {
                    return n.doubleValue();
                }
                case Number n when targetType == float.class -> {
                    return n.floatValue();
                }
                case Number n when targetType == short.class -> {
                    return n.shortValue();
                }
                case Number n when targetType == byte.class -> {
                    return n.byteValue();
                }
                case Boolean b when targetType == boolean.class -> {
                    return b;
                }
                case Character c when targetType == char.class -> {
                    return c;
                }
                case String s when targetType == char.class && s.length() == 1 -> {
                    return s.charAt(0);
                }
                default -> {
                }
            }
        }

        if (Number.class.isAssignableFrom(targetType) && value instanceof Number n) {
            if (targetType == Integer.class) return n.intValue();
            if (targetType == Long.class) return n.longValue();
            if (targetType == Double.class) return n.doubleValue();
            if (targetType == Float.class) return n.floatValue();
            if (targetType == Short.class) return n.shortValue();
            if (targetType == Byte.class) return n.byteValue();
        }

        if (typeResolverRegistry != null && typeResolverRegistry.hasResolver(targetType)) {
            return typeResolverRegistry.resolve(targetType).resolve(new MongoDatabaseResult(doc), field.name());
        }

        return value;
    }

    private boolean isPrimaryKeyField(String fieldName) {
        for (FieldData<?> field : repoInfo.getFields()) {
            if (field.name().equals(fieldName) && field.primary()) {
                return true;
            }
        }
        return false;
    }

    private void loadManyToOne(
        FieldData<?> field,
        Object entity,
        Document doc,
        Set<LoadingKey> loadingContext
    ) {
        Object foreignKeyValue = doc.get(field.name());
        if (foreignKeyValue == null) return;

        LoadingKey key = new LoadingKey(field.type(), foreignKeyValue);
        if (loadingContext.contains(key)) return;

        var adapter = RepositoryRegistry.get(field.type());
        if (adapter == null) return;

        Object result = adapter.first(
            Query.select().where("_id").eq(foreignKeyValue).build()
        );

        if (result != null) {
            field.setValue(entity, result);
        }
    }

    private void loadOneToOne(
        FieldData<?> field,
        Object entity,
        Document doc,
        ID id,
        Set<LoadingKey> loadingContext
    ) {
        if (id == null) return;

        var adapter = RepositoryRegistry.get(field.type());
        if (adapter == null) return;

        OneToOne oneToOne = field.oneToOne();
        RepositoryInformation targetInfo = adapter.getRepositoryInformation();

        // Inverse side
        if (!oneToOne.mappedBy().isEmpty()) {
            FieldData<?> owningField = targetInfo.getField(oneToOne.mappedBy());
            if (owningField == null) return;

            Object child = adapter.first(
                Query.select().where(owningField.name()).eq(id).build()
            );

            if (child != null) {
                field.setValue(entity, child);
            }
            return;
        }

        // Owning side
        Object foreignKeyValue = doc.get(field.name());
        if (foreignKeyValue == null) return;

        LoadingKey key = new LoadingKey(field.type(), foreignKeyValue);
        if (loadingContext.contains(key)) return;

        Object child = adapter.first(
            Query.select().where("_id").eq(foreignKeyValue).build()
        );

        if (child != null) {
            field.setValue(entity, child);
        }
    }

    private void loadOneToMany(
        FieldData<?> field,
        Object parentEntity,
        ID parentId
    ) {
        if (parentId == null) return;

        Class<?> childType = field.oneToMany().mappedBy();
        RepositoryInformation childInfo = RepositoryMetadata.getMetadata(childType);
        if (childInfo == null) return;
        var adapter = RepositoryRegistry.get(childType);
        if (adapter == null) return;

        FieldData<?> manyToOneField =
            childInfo.getManyToOneFieldFor(parentEntity.getClass());

        if (manyToOneField == null) return;

        SelectQuery query = Query.select()
            .where(manyToOneField.name()).eq(parentId)
            .build();

        List<Object> children = new ArrayList<>();
        for (Object child : adapter.find(query)) {
            manyToOneField.setValue(child, parentEntity);
            children.add(child);
        }

        field.setValue(parentEntity, children);
    }
}