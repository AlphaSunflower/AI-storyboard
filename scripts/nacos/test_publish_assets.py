from __future__ import annotations

import io
import unittest
import urllib.error
from unittest import mock

import publish_assets as pipeline


class NacosApiTests(unittest.TestCase):

    def test_login_does_not_recurse(self) -> None:
        class ProbeApi(pipeline.NacosApi):
            def __init__(self) -> None:
                super().__init__("localhost:8848", 1, username="user", password="password")
                self.authenticate_flags: list[bool] = []

            def _request_bytes(self, *args, **kwargs):
                self.authenticate_flags.append(kwargs.get("authenticate", True))
                return {"accessToken": "token"}

        api = ProbeApi()

        api.login_if_needed()

        self.assertEqual([False], api.authenticate_flags)
        self.assertEqual("token", api.access_token)

    def test_404_is_not_found_only_for_get(self) -> None:
        api = pipeline.NacosApi("localhost:8848", 1)
        not_found = urllib.error.HTTPError(
            "http://localhost:8848/test",
            404,
            "not found",
            {},
            io.BytesIO(b"not found"),
        )

        with mock.patch("urllib.request.urlopen", side_effect=not_found):
            self.assertIsNone(api.get("/test"))

        with mock.patch("urllib.request.urlopen", side_effect=not_found):
            with self.assertRaises(pipeline.NacosApiError):
                api.post_form("/test", {})


class VerificationTests(unittest.TestCase):

    def test_skill_label_verification_never_repairs(self) -> None:
        class StubApi:
            def __init__(self) -> None:
                self.put_called = False

            def get(self, path, params=None):
                return {"labels": {"stable": "1.0.0"}}

            def put_form(self, path, form=None):
                self.put_called = True

        api = StubApi()

        with self.assertRaises(pipeline.AssetError):
            pipeline.assert_skill_label(api, "public", "image", "stable", "3.0.0")

        self.assertFalse(api.put_called)

    def test_mcp_verification_compares_schema_shape(self) -> None:
        server = {
            "name": "llm-gateway-media",
            "version": "1.0.0",
            "description": "test",
            "protocol": "mcp-streamable",
            "endpoint": {
                "type": "DIRECT",
                "data": {"address": "127.0.0.2", "port": "8080"},
            },
            "tools": [
                {
                    "name": "generate_image",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"prompt": {"type": "string"}},
                        "required": ["prompt"],
                        "additionalProperties": False,
                    },
                }
            ],
        }
        expected = pipeline.build_mcp_payload(server, "public")
        remote = {
            "name": "llm-gateway-media",
            "versionDetail": {"version": "1.0.0"},
            "toolSpec": {
                "tools": [
                    {
                        "name": "generate_image",
                        "inputSchema": {
                            "type": "object",
                            "properties": {"sourceImages": {"type": "array"}},
                            "required": ["sourceImages"],
                        },
                    }
                ]
            },
        }

        with self.assertRaises(pipeline.AssetError):
            pipeline.assert_mcp_matches(remote, expected)

    def test_manifest_mcp_fields_match_gateway_signatures(self) -> None:
        manifest, _ = pipeline.load_manifest(pipeline.DEFAULT_MANIFEST)
        servers = pipeline.load_mcp_servers(pipeline.DEFAULT_MANIFEST, manifest)
        by_name = {server["name"]: server for server in servers}
        self.assertEqual({"moon-agent", "llm-gateway-media"}, set(by_name))
        tools = {
            tool["name"]: tool["inputSchema"]
            for tool in by_name["llm-gateway-media"]["tools"]
        }

        self.assertEqual(
            {
                "mode", "prompt", "sourceImages", "model", "size", "quality",
                "n", "negativePrompt", "projectId", "conversationId", "idempotencyKey",
            },
            set(tools["generate_image"]["properties"]),
        )
        self.assertEqual(
            {
                "prompt", "referenceImages", "model", "duration", "aspectRatio",
                "resolution", "size", "negativePrompt", "seed", "imageUrl",
                "referenceVideos", "referenceAudios", "projectId", "conversationId",
                "idempotencyKey",
            },
            set(tools["generate_video"]["properties"]),
        )
        self.assertEqual(["prompt"], tools["generate_image"]["required"])
        self.assertEqual(["prompt"], tools["generate_video"]["required"])

        moon_tools = {
            tool["name"]: tool["inputSchema"]
            for tool in by_name["moon-agent"]["tools"]
        }
        self.assertEqual(
            {"writeScenes", "replaceScenes", "replaceSpecificScenes", "refineImage", "generateVideo"},
            set(moon_tools),
        )
        self.assertIn("scenes", moon_tools["writeScenes"]["properties"])
        self.assertIn("picUrls", moon_tools["generateVideo"]["properties"])

    def test_prompt_publication_waits_until_visible(self) -> None:
        class SequencedApi:
            def __init__(self) -> None:
                self.calls = 0

            def get(self, path, params=None):
                self.calls += 1
                if self.calls == 1:
                    return None
                return {"template": "ready"}

        api = SequencedApi()
        prompt = {"key": "global", "version": "1.0.0", "template": "ready"}

        with mock.patch("time.sleep", return_value=None):
            pipeline.wait_for_prompt_publish(api, "public", prompt, 2)

        self.assertEqual(2, api.calls)

    def test_force_uses_force_publish_when_pipeline_blocks(self) -> None:
        class ForceApi:
            def __init__(self) -> None:
                self.get_calls = 0
                self.force_called = False

            def get(self, path, params=None):
                self.get_calls += 1
                status = "reviewing" if self.get_calls == 1 else "online"
                return {"versions": [{"version": "1.0.0", "status": status}]}

            def post_form(self, path, form=None):
                if path.endswith("/publish"):
                    raise pipeline.NacosApiError("pipeline not approved")
                if path.endswith("/force-publish"):
                    self.force_called = True
                    return "ok"
                raise AssertionError(path)

        api = ForceApi()
        with mock.patch("time.sleep", return_value=None):
            pipeline.wait_for_skill_publish(
                api, "public", "image", "1.0.0", True, 2
            )

        self.assertTrue(api.force_called)


if __name__ == "__main__":
    unittest.main()
