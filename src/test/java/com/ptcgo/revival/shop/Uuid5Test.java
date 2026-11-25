package com.ptcgo.revival.shop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class Uuid5Test {
    @Test
    void matchesPythonUuid5() {
        // Values taken from the Python catalogue export (shop_defaults.json / products.json).
        assertEquals("5cea7eba-8931-5ced-9798-6551cf810071", PackOpener.COIN_BOOSTER_GUID);
        assertEquals("e29e9ec0-2dbb-5118-822d-7fa14211cb1e",
                Uuid5.of(Uuid5.NAMESPACE_URL, "ptcgo:deck:dragapult-ex-shop").toString());
        assertEquals("7b51ee25-fbfb-5bcd-97f9-618e7b9e7acb",
                Uuid5.of(Uuid5.NAMESPACE_URL, "ptcgo:product:bundle:charizard-ex-collection").toString());
    }

    @Test
    void skuGuidIsStableAndDistinct() {
        String sku = ShopProduct.skuGuid("5cea7eba-8931-5ced-9798-6551cf810071");
        assertEquals(sku, ShopProduct.skuGuid("5cea7eba-8931-5ced-9798-6551cf810071"));
        assertNotEquals("5cea7eba-8931-5ced-9798-6551cf810071", sku);
    }
}
