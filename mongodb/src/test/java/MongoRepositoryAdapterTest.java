import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.github.flameyossnowy.universal.api.utils.Logging;
import io.github.flameyossnowy.universal.mongodb.MongoRepositoryAdapter;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MongoRepositoryAdapterTest {
    static MongoClient mongoClient;
    static MongoDatabase mongoDatabase;

    static MongoCollection<Document> userCollection;
    static MongoCollection<Document> teamCollection;
    static MongoCollection<Document> playerCollection;
    static MongoCollection<Document> factionCollection;
    static MongoCollection<Document> warpCollection;

    MongoRepositoryAdapter<Main.User, UUID> adapter;
    MongoRepositoryAdapter<Main.TeamRel, UUID> teamAdapter;
    MongoRepositoryAdapter<Main.PlayerRel, UUID> playerAdapter;
    MongoRepositoryAdapter<Main.FactionRel, UUID> factionAdapter;
    MongoRepositoryAdapter<Main.WarpRel, UUID> warpAdapter;

    @BeforeAll
    static void initMongo() {
        Logging.ENABLED = true;
        mongoClient = MongoClients.create(MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(
                "mongodb+srv://flameyosflow:...@testingjava.vmol6.mongodb.net/?retryWrites=true&w=majority&appName=TestingJava"
            ))
            .uuidRepresentation(UuidRepresentation.STANDARD)
            .build());

        mongoDatabase = mongoClient.getDatabase("users");

        userCollection = mongoDatabase.getCollection("users_old");

        userCollection = mongoDatabase.getCollection("users_old");
        teamCollection = mongoDatabase.getCollection("teams_rel");
        playerCollection = mongoDatabase.getCollection("players_rel");
        factionCollection = mongoDatabase.getCollection("factions_rel");
        warpCollection = mongoDatabase.getCollection("warps_rel");
    }

    @AfterAll
    static void closeMongo() {
        mongoClient.close();
    }

    @BeforeEach
    void setup() {
        userCollection.deleteMany(new Document());
        teamCollection.deleteMany(new Document());
        playerCollection.deleteMany(new Document());
        factionCollection.deleteMany(new Document());
        warpCollection.deleteMany(new Document());

        adapter = MongoRepositoryAdapter
            .builder(Main.User.class, UUID.class)
            .withClient(mongoClient)
            .setDatabase("users")
            .build();

        teamAdapter = MongoRepositoryAdapter
            .builder(Main.TeamRel.class, UUID.class)
            .withClient(mongoClient)
            .setDatabase("users")
            .build();

        playerAdapter = MongoRepositoryAdapter
            .builder(Main.PlayerRel.class, UUID.class)
            .withClient(mongoClient)
            .setDatabase("users")
            .build();

        factionAdapter = MongoRepositoryAdapter
            .builder(Main.FactionRel.class, UUID.class)
            .withClient(mongoClient)
            .setDatabase("users")
            .build();

        warpAdapter = MongoRepositoryAdapter
            .builder(Main.WarpRel.class, UUID.class)
            .withClient(mongoClient)
            .setDatabase("users")
            .build();
    }

    @Test
    void insert_new_user_inserts_document() {
        Main.User user = new Main.User(
            UUID.randomUUID(),
            "Flow",
            21,
            Instant.now()
        );

        adapter.insert(user);

        Document found = userCollection
            .find(new Document("_id", user.getId()))
            .first();

        assertNotNull(found);
    }

    @Test
    void insert_existing_user_does_not_insert() {
        UUID id = UUID.randomUUID();

        userCollection.insertOne(
            new Document("_id", id)
        );

        Main.User user = new Main.User(
            id,
            "Flow",
            21,
            Instant.now()
        );

        adapter.insert(user);

        long count = userCollection.countDocuments(
            new Document("_id", id)
        );

        assertEquals(1, count);
    }

    @Test
    void delete_existing_user_deletes_document() {
        UUID id = UUID.randomUUID();

        userCollection.insertOne(
            new Document("_id", id)
        );

        adapter.deleteById(id);

        Document found = userCollection
            .find(new Document("_id", id))
            .first();

        assertNull(found);
    }

    @Test
    void delete_non_existing_user_does_not_delete() {
        UUID id = UUID.randomUUID();

        adapter.deleteById(id);

        long count = userCollection.countDocuments(
            new Document("_id", id)
        );

        assertEquals(0, count);
    }

    @Test
    void find_by_id_queries_collection() {
        UUID id = UUID.randomUUID();

        userCollection.insertOne(
            new Document("_id", id)
                .append("username", "Flow")
                .append("age", 20)
                .append("password", Instant.now().toString())
        );

        Main.User user = adapter.findById(id);

        assertNotNull(user);
        assertEquals("Flow", user.getUsername());
    }

    @Test
    void one_to_many_relationship_is_populated_on_findById() {
        UUID teamId = UUID.randomUUID();
        Main.TeamRel team = new Main.TeamRel(teamId, "TeamA");
        teamAdapter.insert(team)
            .ifError(Throwable::printStackTrace);

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        playerAdapter.insert(new Main.PlayerRel(p1, "P1", team))
            .ifError(Throwable::printStackTrace);
        playerAdapter.insert(new Main.PlayerRel(p2, "P2", team))
            .ifError(Throwable::printStackTrace);

        Main.TeamRel loaded = teamAdapter.findById(teamId);
        assertNotNull(loaded);
        System.out.println(loaded.getId());
        System.out.println(loaded.getName());
        System.out.println(loaded.getPlayers());

        assertNotNull(loaded.getPlayers());
        assertEquals(2, loaded.getPlayers().size());
    }

    @Test
    void one_to_one_relationship_is_populated_on_findById() {
        UUID factionId = UUID.randomUUID();
        UUID warpId = UUID.randomUUID();

        Main.FactionRel faction = new Main.FactionRel(factionId, "FactionA");
        factionAdapter.insert(faction)
            .ifError(Throwable::printStackTrace);

        warpAdapter.insert(new Main.WarpRel(warpId, "WarpA", faction))
            .ifError(Throwable::printStackTrace);

        Main.FactionRel loaded = factionAdapter.findById(factionId);
        assertNotNull(loaded);
        assertNotNull(loaded.getWarp());
        assertEquals("WarpA", loaded.getWarp().getName());
    }
}
