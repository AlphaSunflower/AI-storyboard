package com.storyboard.asset.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsIdempotencyAndCorrelationHeadersForMutations() throws IOException {
        AtomicReference<String> idempotency = new AtomicReference<>();
        AtomicReference<String> correlation = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/library/assets", exchange -> {
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            respond(exchange, 200, """
                    {"code":0,"message":"ok","data":{"id":"a1","name":"demo","type":"IMAGE","scope":"PROJECT","status":"ACTIVE","version":1,"tags":[]}}
                    """);
        });
        server.start();

        AssetClient client = AssetClient.create(new AssetClientProperties(
                "http://localhost:" + server.getAddress().getPort(), "secret", 30, 10));
        client.createAsset(
                new AssetContracts.CreateAssetRequest("u1", "p1", "IMAGE", null, "demo", null, "upload"),
                "idem-1",
                "corr-1");

        assertThat(idempotency.get()).isEqualTo("idem-1");
        assertThat(correlation.get()).isEqualTo("corr-1");
    }

    @Test
    void preservesBusinessCodeFromNonSuccessfulResponses() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/internal/library/assets/by-source", exchange -> {
            respond(exchange, 404, """
                    {"code":40401,"message":"来源媒体不存在","data":null,"timestamp":"2026-09-21T16:00:00+08:00"}
                    """);
        });
        server.start();

        AssetClient client = AssetClient.create(new AssetClientProperties(
                "http://localhost:" + server.getAddress().getPort(), "secret", 30, 10));

        assertThatThrownBy(() -> client.findAssetBySource("u1", "canvas-upload", "missing", "corr-1"))
                .isInstanceOfSatisfying(AssetServiceException.class, error -> {
                    assertThat(error.code()).isEqualTo(40401);
                    assertThat(error.httpStatus()).isEqualTo(404);
                    assertThat(error.getMessage()).isEqualTo("来源媒体不存在");
                });
    }

    @Test
    void ignoresEnvelopeFieldsAddedByTheService() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/library/assets", exchange -> {
            respond(exchange, 200, """
                    {
                      "code":200,
                      "message":"success",
                      "data":{"records":[],"total":0,"page":1,"size":20},
                      "timestamp":"2026-09-21T16:00:00+08:00"
                    }
                    """);
        });
        server.start();

        AssetClient client = AssetClient.create(new AssetClientProperties(
                "http://localhost:" + server.getAddress().getPort(), "secret", 30, 10));

        var page = client.listAssets(
                AssetContracts.AssetScope.GLOBAL,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                1,
                20
        );

        assertThat(page.records()).isEmpty();
        assertThat(page.total()).isZero();
    }

    @Test
    void readsGroupDetailsUsingTheInternalActorIdentity() throws IOException {
        AtomicReference<String> internalToken = new AtomicReference<>();
        AtomicReference<String> actingUser = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/library/groups/group-1", exchange -> {
            internalToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            actingUser.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
            respond(exchange, 200, """
                    {
                      "code":200,
                      "message":"success",
                      "data":{
                        "groupId":"group-1",
                        "name":"主角集",
                        "description":null,
                        "version":3,
                        "media":[{
                          "media":{
                            "id":"media-1","blobId":"blob-1","type":"IMAGE",
                            "url":"/api/files/images/reference.png",
                            "contentUrl":"/api/library/media/media-1/content",
                            "objectKey":"images/reference.png","fileName":"reference.png",
                            "relativePath":null,"mimeType":"image/png","sizeBytes":123,
                            "width":640,"height":480,"durationSeconds":null,"sortOrder":0,
                            "role":"REFERENCE","primary":true,"constraintText":null
                          },
                          "assetId":"asset-1","assetName":"参考图","assetDescription":null,
                          "assetVersion":1,"assetTags":[]
                        }]
                      },
                      "timestamp":"2026-09-21T16:00:00+08:00"
                    }
                    """);
        });
        server.start();

        AssetClient client = AssetClient.create(new AssetClientProperties(
                "http://localhost:" + server.getAddress().getPort(), "secret", 30, 10));
        var group = client.getGroupInternal("group-1", "u1");

        assertThat(internalToken.get()).isEqualTo("secret");
        assertThat(actingUser.get()).isEqualTo("u1");
        assertThat(group.groupId()).isEqualTo("group-1");
        assertThat(group.media()).singleElement().satisfies(item -> {
            assertThat(item.assetId()).isEqualTo("asset-1");
            assertThat(item.media().objectKey()).isEqualTo("images/reference.png");
            assertThat(item.media().type()).isEqualTo(AssetContracts.MediaType.IMAGE);
        });
    }

    @Test
    void ignoresAssetFieldsAddedByTheService() throws IOException {
        // 回归：asset-service 与消费方各自独立打包，服务端先加字段是常态。
        // 2026-10-19 画布服务因为跑的是旧包、而 asset-service 已多返回 conversationId，
        // 在 by-source 上直接解析失败，画布资产同步永久卡在 failed。
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/internal/library/assets/by-source", exchange -> {
            respond(exchange, 200, """
                    {
                      "code":200,
                      "message":"success",
                      "data":{"asset":{
                        "id":"asset-1","ownerUserId":"u1","scope":"PROJECT","ownerProjectId":"p1",
                        "type":"IMAGE","name":"画布产出","description":null,"aiDescription":null,
                        "manualOverride":false,"metadataState":"NONE","status":"ACTIVE",
                        "coverUrl":null,"version":1,"tags":[],"projects":[],
                        "folderId":null,"folderPath":[],"sourceType":"canvas-generation",
                        "sourceId":"gen-1:frame.png","createdAt":"2026-10-19T14:00:00+08:00",
                        "updatedAt":"2026-10-19T14:00:00+08:00",
                        "conversationId":"conv-1",
                        "brandNewServerField":"服务端将来才会加的字段"
                      }},
                      "timestamp":"2026-10-19T14:00:00+08:00"
                    }
                    """);
        });
        server.start();

        AssetClient client = AssetClient.create(new AssetClientProperties(
                "http://localhost:" + server.getAddress().getPort(), "secret", 30, 10));
        var found = client.findAssetBySource("u1", "canvas-generation", "gen-1:frame.png", "corr-1");

        assertThat(found.asset().id()).isEqualTo("asset-1");
        assertThat(found.asset().conversationId()).isEqualTo("conv-1");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
