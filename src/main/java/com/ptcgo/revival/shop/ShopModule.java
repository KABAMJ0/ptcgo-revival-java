package com.ptcgo.revival.shop;

import com.ptcgo.revival.Config;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.packet.RedemptionHandler;
import com.ptcgo.revival.packet.ShopHandler;

import java.io.IOException;
import java.util.random.RandomGenerator;

/** Wires the shop, pack opening, redemption and starter content together. */
public final class ShopModule {
    public final ShopProducts products;
    public final ShopRepository shopRepository;
    public final CodeRepository codes;
    public final ShopManager shopManager;
    public final PackOpener opener;
    public final StarterContent starter;

    private ShopModule(ShopProducts products, ShopRepository shopRepository, CodeRepository codes, ShopManager shopManager,
                       PackOpener opener, StarterContent starter) {
        this.products = products;
        this.shopRepository = shopRepository;
        this.codes = codes;
        this.shopManager = shopManager;
        this.opener = opener;
        this.starter = starter;
    }

    /** Production wiring: products and shop defaults from {@code cfg.catalogDir}, a fresh RNG, shipped rule files. */
    public static ShopModule create(Config cfg, Database db, Catalog catalog) throws IOException {
        ShopProducts products = ShopProducts.load(cfg.catalogDir);
        ShopRepository repo = new ShopRepository(db);
        repo.ensureDefaultShopItems(ShopRepository.loadDefaultOffers(cfg.catalogDir));
        return create(db, catalog, products, repo, cfg.clientBaseUrl(null) + "/placeholder.png", RandomGenerator.getDefault());
    }

    public static ShopModule create(Database db, Catalog catalog, ShopProducts products, ShopRepository repo,
                                    String placeholderImage, RandomGenerator rng) {
        PackMerges merges = PackMerges.defaults();
        PackOpener opener = new PackOpener(catalog, products, PackRules.defaults(), merges, rng);
        ShopManager manager = new ShopManager(products, catalog, repo, merges, placeholderImage);
        return new ShopModule(products, repo, new CodeRepository(db, rng), manager, opener,
                new StarterContent(db, catalog, products, merges));
    }

    public ShopHandler shopHandler() {
        return new ShopHandler(shopManager, shopRepository, products, opener);
    }

    public RedemptionHandler redemptionHandler() {
        return new RedemptionHandler(codes);
    }
}
