package exchange.core2.rest.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import exchange.core2.rest.model.api.RestApiAsset;
import org.springframework.stereotype.Component;

@Component
public class AssetRepository {
    private final MongoCollection<RestApiAsset> collection;

    public AssetRepository(MongoDatabase database) {
        this.collection = database.getCollection(RestApiAsset.class.getSimpleName().toLowerCase(), RestApiAsset.class);
        this.collection.createIndex(Indexes.descending("assetId"), new IndexOptions().unique(true));
    }

    public RestApiAsset findByAssetId(String assetId) {
        return this.collection
                .find(Filters.eq("_id", assetId))
                .first();
    }

    public void save(RestApiAsset asset) {
        this.collection.insertOne(asset);
    }

}
