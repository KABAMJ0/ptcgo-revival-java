package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.shop.CodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The Shop's code redemption flow (ValidateCode / RedeemCodes); ptcgo/packets/handlers/redemption.py. */
public final class RedemptionHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(RedemptionHandler.class);
    private final CodeRepository codes;

    public RedemptionHandler(CodeRepository codes) {
        this.codes = codes;
    }

    @Override
    public Map<String, MessageHandler> handlers() {
        Map<String, MessageHandler> m = new HashMap<>();
        m.put("ValidateCode", this::validateCode);
        m.put("RedeemCodes", this::redeemCodes);
        return m;
    }

    private static void sendInvalid(ClientContext c, String code, String reason, int rid, String messageName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("reason", Map.of("id", reason == null || reason.isEmpty() ? CodeRepository.CODE_INVALID : reason));
        c.send(messageName, m, rid);
    }

    private void validateCode(ClientContext c, JsonNode v, int rid, int flags) throws SQLException {
        String code = text(v, "code").strip().toUpperCase(Locale.ROOT);
        LOG.info("[TCP] [{}] Client validating redemption code: {}", c.remoteAddress(), code);
        if (c.account() == null) {
            sendInvalid(c, code, null, rid, "InvalidCode");
            return;
        }
        CodeRepository.Result r = codes.check(code, c.account().accountId());
        if (!r.ok()) {
            sendInvalid(c, code, r.reason(), rid, "InvalidCode");
            return;
        }
        List<String> products = new ArrayList<>();
        if (r.reward() != null) r.reward().path("products").fieldNames().forEachRemaining(products::add);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("products", products);
        m.put("couponGroup", code);
        m.put("maxRedemptionsForGroup", null);
        c.send("CodeIsValid", m, rid);
    }

    private void redeemCodes(ClientContext c, JsonNode v, int rid, int flags) throws SQLException {
        List<String> list = new ArrayList<>();
        JsonNode raw = v == null ? null : v.get("codes");
        if (raw != null && raw.isArray()) for (JsonNode n : raw) list.add(n.isNull() ? "" : n.asText());
        LOG.info("[TCP] [{}] Client redeeming codes: {}", c.remoteAddress(), list);
        if (c.account() == null) {
            for (String code : list) sendInvalid(c, code, null, rid, "CodeRedemptionFailure");
            return;
        }
        String account = c.account().accountId();
        boolean any = false;
        for (String raw1 : list) {
            String code = raw1.strip().toUpperCase(Locale.ROOT);
            CodeRepository.Result r = codes.redeem(code, account);
            if (r.ok()) {
                any = true;
                c.send("CodeSuccessfullyRedeemed", Map.of("code", code), rid);
                LOG.info("[TCP] Code {} redeemed by {}: {}", code, account, r.reward());
            } else {
                sendInvalid(c, code, r.reason(), rid, "CodeRedemptionFailure");
            }
        }
        if (any) {
            c.send("CollectionCountFound", Map.of("collectionCountList", DataSyncHandler.collectionPayload(c)), 0);
            c.send("CurrentWallet", DataSyncHandler.walletPayload(c.services().players().wallet(account)), 0);
        }
    }

    private static String text(JsonNode v, String field) {
        return v != null && v.hasNonNull(field) ? v.get(field).asText() : "";
    }
}
