#!/usr/bin/env python3
"""Publish and verify versioned MoonAgent assets in Nacos.

Credentials are read only from command-line options or environment variables
and are never written to the manifest or report.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = REPO_ROOT / "MoonAgent/src/main/resources/agent-assets/nacos-assets.json"
SUCCESS_CODE = 0
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


class AssetError(RuntimeError):
    pass


class NacosApiError(AssetError):
    pass


@dataclass(frozen=True)
class SkillArtifact:
    name: str
    version: str
    archive: bytes
    entries: dict[str, bytes]
    content_hash: str


class NacosApi:
    def __init__(
        self,
        server_address: str,
        timeout: float,
        access_token: str = "",
        username: str = "",
        password: str = "",
    ) -> None:
        if not server_address:
            raise AssetError("Nacos server address is required")
        normalized = server_address.strip()
        if "://" not in normalized:
            normalized = "http://" + normalized
        self.base_url = normalized.rstrip("/")
        self.timeout = timeout
        self.access_token = access_token.strip()
        self.username = username.strip()
        self.password = password

    def login_if_needed(self) -> None:
        if self.access_token or not self.username:
            return
        payload = self._request_form(
            "POST",
            "/v1/auth/login",
            {"username": self.username, "password": self.password},
        )
        token = ""
        if isinstance(payload, dict):
            token = str(payload.get("accessToken") or payload.get("access_token") or "")
        if not token:
            raise NacosApiError("Nacos login succeeded without an access token")
        self.access_token = token

    def get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        return self._request("GET", path, params=params)

    def post_form(self, path: str, form: dict[str, Any] | None = None) -> Any:
        return self._request_form("POST", path, form)

    def put_form(self, path: str, form: dict[str, Any] | None = None) -> Any:
        return self._request_form("PUT", path, form)

    def post_multipart(
        self,
        path: str,
        fields: dict[str, Any],
        file_field: str,
        filename: str,
        file_bytes: bytes,
    ) -> Any:
        boundary = "----moonagent-" + uuid.uuid4().hex
        chunks: list[bytes] = []
        for key, value in fields.items():
            chunks.extend(
                [
                    f"--{boundary}\r\n".encode(),
                    f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode(),
                    str(value).encode(),
                    b"\r\n",
                ]
            )
        chunks.extend(
            [
                f"--{boundary}\r\n".encode(),
                (
                    f'Content-Disposition: form-data; name="{file_field}"; '
                    f'filename="{filename}"\r\n'
                ).encode(),
                b"Content-Type: application/zip\r\n\r\n",
                file_bytes,
                b"\r\n",
                f"--{boundary}--\r\n".encode(),
            ]
        )
        body = b"".join(chunks)
        headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
        return self._request_bytes("POST", path, body=body, headers=headers, expect_json=True)

    def get_raw(self, path: str, params: dict[str, Any] | None = None) -> bytes | None:
        return self._request_bytes("GET", path, params=params, expect_json=False)

    def _request_form(
        self,
        method: str,
        path: str,
        form: dict[str, Any] | None,
    ) -> Any:
        body = urllib.parse.urlencode(form or {}).encode()
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
        return self._request_bytes(method, path, body=body, headers=headers, expect_json=True)

    def _request(
        self,
        method: str,
        path: str,
        params: dict[str, Any] | None = None,
    ) -> Any:
        return self._request_bytes(method, path, params=params, expect_json=True)

    def _request_bytes(
        self,
        method: str,
        path: str,
        params: dict[str, Any] | None = None,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
        expect_json: bool = True,
    ) -> Any:
        self.login_if_needed()
        url = self.base_url + path
        if params:
            query = urllib.parse.urlencode(
                {key: value for key, value in params.items() if value is not None}
            )
            if query:
                url += "?" + query
        request_headers = {"Accept": "application/json, application/zip, */*"}
        request_headers.update(headers or {})
        if self.access_token:
            request_headers["accessToken"] = self.access_token
        request = urllib.request.Request(
            url,
            data=body,
            headers=request_headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                response_body = response.read()
        except urllib.error.HTTPError as error:
            error_body = error.read()
            if error.code == 404:
                return None
            raise NacosApiError(
                f"Nacos {method} {path} failed with HTTP {error.code}: "
                f"{_decode_error(error_body)}"
            ) from error
        except urllib.error.URLError as error:
            raise NacosApiError(f"Nacos {method} {path} is unreachable: {error.reason}") from error

        if not expect_json:
            return response_body
        if not response_body:
            return None
        try:
            payload = json.loads(response_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise NacosApiError(
                f"Nacos {method} {path} returned invalid JSON: {_decode_error(response_body)}"
            ) from error
        if isinstance(payload, dict) and "code" in payload:
            if payload.get("code") != SUCCESS_CODE:
                message = payload.get("message") or payload.get("data") or "unknown Nacos error"
                raise NacosApiError(
                    f"Nacos {method} {path} failed with code {payload.get('code')}: {message}"
                )
            return payload.get("data")
        return payload


def _decode_error(body: bytes) -> str:
    if not body:
        return "<empty response>"
    text = body.decode("utf-8", errors="replace")
    return text[:1000]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=("verify", "publish"),
        help="verify existing assets or publish pending versions",
    )
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--server-addr", default=os.getenv("NACOS_ADDR", ""))
    parser.add_argument("--namespace", default=os.getenv("NACOS_NAMESPACE", ""))
    parser.add_argument("--label", default=os.getenv("AGENTSCOPE_NACOS_SKILL_LABEL", ""))
    parser.add_argument("--token", default=os.getenv("NACOS_ACCESS_TOKEN", ""))
    parser.add_argument("--username", default=os.getenv("NACOS_USERNAME", ""))
    parser.add_argument("--password", default=os.getenv("NACOS_PASSWORD", ""))
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--offline", action="store_true", help="verify local packages only")
    parser.add_argument("--force", action="store_true", help="allow overwrite/force paths")
    parser.add_argument("--json", action="store_true", help="emit a JSON report")
    return parser.parse_args()


def load_manifest(path: Path) -> tuple[dict[str, Any], list[SkillArtifact]]:
    manifest_path = path if path.is_absolute() else (REPO_ROOT / path)
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AssetError(f"Unable to read manifest {manifest_path}: {error}") from error
    if manifest.get("schemaVersion") != 1:
        raise AssetError("Unsupported asset manifest schemaVersion")

    skills = manifest.get("skills")
    if not isinstance(skills, list) or not skills:
        raise AssetError("Manifest skills must be a non-empty array")
    artifacts = [
        build_skill_artifact(manifest_path, entry)
        for entry in skills
        if isinstance(entry, dict)
    ]
    if len(artifacts) != len(skills):
        raise AssetError("Every skill manifest entry must be an object")
    return manifest, artifacts


def build_skill_artifact(manifest_path: Path, entry: dict[str, Any]) -> SkillArtifact:
    name = required_text(entry, "name")
    version = required_text(entry, "version")
    skill_dir = resolve_manifest_path(manifest_path, required_text(entry, "path"))
    skill_md = skill_dir / "SKILL.md"
    if not skill_md.is_file():
        raise AssetError(f"Skill {name} has no SKILL.md at {skill_md}")

    source_text = skill_md.read_text(encoding="utf-8")
    frontmatter_name = frontmatter_value(source_text, "name")
    if frontmatter_name != name:
        raise AssetError(
            f"Skill manifest name {name!r} does not match SKILL.md name {frontmatter_name!r}"
        )
    normalized_skill_md = set_frontmatter_version(source_text, version)

    entries: dict[str, bytes] = {}
    for file_path in sorted(path for path in skill_dir.rglob("*") if path.is_file()):
        relative = file_path.relative_to(skill_dir).as_posix()
        if relative.startswith(".") or "/." in relative:
            continue
        data = normalized_skill_md.encode("utf-8") if relative == "SKILL.md" else file_path.read_bytes()
        entries[f"{name}/{relative}"] = data
    if not entries:
        raise AssetError(f"Skill {name} produced an empty package")
    return SkillArtifact(
        name=name,
        version=version,
        archive=build_deterministic_zip(entries),
        entries=entries,
        content_hash=canonical_entries_hash(entries),
    )


def required_text(mapping: dict[str, Any], key: str) -> str:
    value = mapping.get(key)
    if value is None or not str(value).strip():
        raise AssetError(f"Manifest field {key!r} is required")
    return str(value).strip()


def resolve_manifest_path(manifest_path: Path, raw_path: str) -> Path:
    path = Path(raw_path)
    if path.is_absolute():
        return path
    candidate = REPO_ROOT / path
    if candidate.exists():
        return candidate
    return manifest_path.parent / path


def frontmatter_value(markdown: str, key: str) -> str:
    match = re.match(r"^---\s*\r?\n(.*?)\r?\n---(?:\r?\n|$)", markdown, re.DOTALL)
    if not match:
        raise AssetError("SKILL.md does not contain YAML frontmatter")
    pattern = re.compile(rf"^{re.escape(key)}\s*:\s*(.+?)\s*$", re.MULTILINE)
    value_match = pattern.search(match.group(1))
    if not value_match:
        raise AssetError(f"SKILL.md frontmatter has no top-level {key!r}")
    return value_match.group(1).strip().strip("\"'")


def set_frontmatter_version(markdown: str, version: str) -> str:
    match = re.match(r"^---\s*\r?\n(.*?)\r?\n---(.*)$", markdown, re.DOTALL)
    if not match:
        raise AssetError("SKILL.md does not contain YAML frontmatter")
    lines = match.group(1).splitlines()
    version_line = f'version: "{version}"'
    for index, line in enumerate(lines):
        if re.match(r"^version\s*:", line):
            lines[index] = version_line
            break
    else:
        insert_at = 0
        for index, line in enumerate(lines):
            if re.match(r"^name\s*:", line):
                insert_at = index + 1
                break
        lines.insert(insert_at, version_line)
    return "---\n" + "\n".join(lines) + "\n---" + match.group(2)


def build_deterministic_zip(entries: dict[str, bytes]) -> bytes:
    output = BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, entries[name])
    return output.getvalue()


def canonical_entries_hash(entries: dict[str, bytes]) -> str:
    digest = hashlib.sha256()
    for name in sorted(entries):
        data = entries[name]
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(len(data)).encode("ascii"))
        digest.update(b"\0")
        digest.update(hashlib.sha256(data).digest())
    return digest.hexdigest()


def zip_entries(raw: bytes) -> dict[str, bytes]:
    with zipfile.ZipFile(BytesIO(raw)) as archive:
        return {
            info.filename.replace("\\", "/"): archive.read(info)
            for info in archive.infolist()
            if not info.is_dir()
        }


def load_global_prompt(manifest_path: Path, manifest: dict[str, Any]) -> dict[str, Any]:
    prompt = manifest.get("globalPrompt")
    if not isinstance(prompt, dict):
        raise AssetError("Manifest globalPrompt is required")
    path = resolve_manifest_path(manifest_path, required_text(prompt, "path"))
    template = normalize_newlines(path.read_text(encoding="utf-8"))
    if not template.strip():
        raise AssetError("Global prompt is empty")
    return {
        "key": required_text(prompt, "key"),
        "version": required_text(prompt, "version"),
        "label": str(prompt.get("label") or "stable").strip(),
        "template": template,
        "hash": hashlib.sha256(template.encode("utf-8")).hexdigest(),
    }


def load_mcp_servers(manifest_path: Path, manifest: dict[str, Any]) -> list[dict[str, Any]]:
    metadata = manifest.get("mcpMetadata")
    if not isinstance(metadata, dict):
        raise AssetError("Manifest mcpMetadata is required")
    metadata_path = resolve_manifest_path(manifest_path, required_text(metadata, "path"))
    try:
        document = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AssetError(f"Unable to read MCP metadata {metadata_path}: {error}") from error
    selected = set(str(value) for value in metadata.get("servers", []))
    servers = document.get("servers")
    if not isinstance(servers, list):
        raise AssetError("MCP metadata must contain a servers array")
    result = [
        server
        for server in servers
        if isinstance(server, dict) and str(server.get("name")) in selected
    ]
    if len(result) != len(selected):
        found = {str(server.get("name")) for server in result}
        raise AssetError(f"MCP metadata is missing servers: {sorted(selected - found)}")
    validate_mcp_servers(result)
    return result


def validate_mcp_servers(servers: list[dict[str, Any]]) -> None:
    for server in servers:
        name = required_text(server, "name")
        if "canvas" in name.lower() or "morrow" in name.lower():
            raise AssetError("Canvas Bridge must not be published to shared Nacos")
        endpoint = json.dumps(server.get("endpoint") or {})
        if "8787" in endpoint or "127.0.0.1" in endpoint or "localhost" in endpoint.lower():
            raise AssetError(f"MCP server {name} contains a local Canvas-style endpoint")
        tools = server.get("tools")
        if not isinstance(tools, list) or not tools:
            raise AssetError(f"MCP server {name} must declare at least one tool")
        for tool in tools:
            if not isinstance(tool, dict) or not str(tool.get("name") or "").strip():
                raise AssetError(f"MCP server {name} has an invalid tool")


def normalize_newlines(value: str) -> str:
    return value.replace("\r\n", "\n").replace("\r", "\n")


def expand_env(value: Any, source: str) -> Any:
    if isinstance(value, dict):
        return {key: expand_env(item, source) for key, item in value.items()}
    if isinstance(value, list):
        return [expand_env(item, source) for item in value]
    if not isinstance(value, str):
        return value

    def replace(match: re.Match[str]) -> str:
        name = match.group(1)
        resolved = os.getenv(name)
        if resolved is None or resolved == "":
            raise AssetError(f"{source} requires environment variable {name}")
        return resolved

    return re.sub(r"\$\{([A-Z0-9_]+)\}", replace, value)


def build_mcp_payload(server: dict[str, Any], namespace: str) -> dict[str, Any]:
    name = required_text(server, "name")
    version = required_text(server, "version")
    protocol = str(server.get("protocol") or "mcp-streamable")
    server_specification = {
        "name": name,
        "protocol": protocol,
        "frontProtocol": protocol,
        "description": str(server.get("description") or ""),
        "versionDetail": {"version": version},
        "enabled": True,
    }
    endpoint = expand_env(server.get("endpoint") or {}, f"MCP server {name}")
    if endpoint:
        endpoint.setdefault("data", {})["namespaceId"] = namespace
    return {
        "name": name,
        "version": version,
        "serverSpecification": server_specification,
        "toolSpecification": {"tools": server.get("tools") or []},
        "endpointSpecification": endpoint or None,
    }


def offline_verify(
    manifest: dict[str, Any],
    artifacts: list[SkillArtifact],
    prompt: dict[str, Any],
    mcp_servers: list[dict[str, Any]],
) -> dict[str, Any]:
    validate_mcp_servers(mcp_servers)
    canvas_names = [
        server.get("name")
        for server in mcp_servers
        if "canvas" in str(server.get("name", "")).lower()
    ]
    if canvas_names:
        raise AssetError(f"Canvas MCP must not be published: {canvas_names}")
    return {
        "mode": "offline",
        "namespace": manifest.get("namespace") or "public",
        "skills": [
            {
                "name": artifact.name,
                "version": artifact.version,
                "sha256": artifact.content_hash,
                "files": len(artifact.entries),
                "zipBytes": len(artifact.archive),
            }
            for artifact in artifacts
        ],
        "globalPrompt": {
            "key": prompt["key"],
            "version": prompt["version"],
            "label": prompt["label"],
            "sha256": prompt["hash"],
        },
        "mcpServers": [
            {"name": server["name"], "version": server["version"]} for server in mcp_servers
        ],
    }


def online_verify(
    api: NacosApi,
    namespace: str,
    label: str,
    artifacts: list[SkillArtifact],
    prompt: dict[str, Any],
    mcp_servers: list[dict[str, Any]],
) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    for artifact in artifacts:
        remote = api.get_raw(
            "/v3/admin/ai/skills/version/download",
            {
                "namespaceId": namespace,
                "skillName": artifact.name,
                "version": artifact.version,
            },
        )
        if remote is None:
            raise AssetError(f"Nacos is missing skill {artifact.name}@{artifact.version}")
        remote_hash = canonical_entries_hash(zip_entries(remote))
        if remote_hash != artifact.content_hash:
            raise AssetError(
                f"Nacos skill hash mismatch for {artifact.name}@{artifact.version}: "
                f"{remote_hash} != {artifact.content_hash}"
            )
        ensure_label(api, namespace, artifact.name, label, artifact.version)
        results.append(
            {"name": artifact.name, "version": artifact.version, "sha256": remote_hash}
        )

    prompt_detail = api.get(
        "/v3/admin/ai/prompt/detail",
        {
            "namespaceId": namespace,
            "promptKey": prompt["key"],
            "version": prompt["version"],
        },
    )
    if not isinstance(prompt_detail, dict):
        raise AssetError(f"Nacos is missing prompt {prompt['key']}@{prompt['version']}")
    remote_template = normalize_newlines(str(prompt_detail.get("template") or ""))
    if remote_template != prompt["template"]:
        raise AssetError(f"Nacos prompt hash mismatch for {prompt['key']}@{prompt['version']}")
    prompt_metadata = api.get(
        "/v3/admin/ai/prompt/metadata",
        {"namespaceId": namespace, "promptKey": prompt["key"]},
    )
    labels = prompt_metadata.get("labels") if isinstance(prompt_metadata, dict) else {}
    if isinstance(labels, dict) and labels.get(prompt["label"]) not in (None, prompt["version"]):
        raise AssetError(
            f"Prompt label {prompt['label']} points to {labels.get(prompt['label'])}, "
            f"expected {prompt['version']}"
        )

    mcp_results = []
    for server in mcp_servers:
        payload = build_mcp_payload(server, namespace)
        remote = api.get(
            "/v3/admin/ai/mcp",
            {
                "namespaceId": namespace,
                "mcpName": payload["name"],
                "version": payload["version"],
            },
        )
        if not isinstance(remote, dict):
            raise AssetError(f"Nacos is missing MCP server {payload['name']}@{payload['version']}")
        assert_mcp_matches(remote, payload)
        mcp_results.append({"name": payload["name"], "version": payload["version"]})
    return {
        "mode": "online",
        "namespace": namespace,
        "label": label,
        "skills": results,
        "globalPrompt": {
            "key": prompt["key"],
            "version": prompt["version"],
            "sha256": prompt["hash"],
        },
        "mcpServers": mcp_results,
    }


def publish_assets(
    api: NacosApi,
    namespace: str,
    label: str,
    artifacts: list[SkillArtifact],
    prompt: dict[str, Any],
    mcp_servers: list[dict[str, Any]],
    force: bool,
) -> dict[str, Any]:
    actions: list[dict[str, Any]] = []
    for artifact in artifacts:
        actions.append(publish_skill(api, namespace, label, artifact, force))
    actions.append(publish_prompt(api, namespace, prompt))
    for server in mcp_servers:
        actions.append(publish_mcp(api, namespace, server))
    return {"mode": "publish", "namespace": namespace, "actions": actions}


def publish_skill(
    api: NacosApi,
    namespace: str,
    label: str,
    artifact: SkillArtifact,
    force: bool,
) -> dict[str, Any]:
    remote = api.get_raw(
        "/v3/admin/ai/skills/version/download",
        {
            "namespaceId": namespace,
            "skillName": artifact.name,
            "version": artifact.version,
        },
    )
    if remote is not None:
        remote_hash = canonical_entries_hash(zip_entries(remote))
        if remote_hash != artifact.content_hash:
            raise AssetError(
                f"Refusing to overwrite immutable skill {artifact.name}@{artifact.version}: "
                "bump the manifest version"
            )
        ensure_label(api, namespace, artifact.name, label, artifact.version)
        return {"asset": "skill", "name": artifact.name, "result": "unchanged"}

    api.post_multipart(
        "/v3/admin/ai/skills/upload",
        {"namespaceId": namespace, "overwrite": "true"},
        "file",
        f"{artifact.name}.zip",
        artifact.archive,
    )
    detail = api.get(
        "/v3/admin/ai/skills",
        {"namespaceId": namespace, "skillName": artifact.name},
    )
    editing_version = detail.get("editingVersion") if isinstance(detail, dict) else None
    if editing_version != artifact.version:
        raise AssetError(
            f"Nacos uploaded {artifact.name} as {editing_version}, "
            f"expected {artifact.version}"
        )

    if force:
        api.post_form(
            "/v3/admin/ai/skills/force-publish",
            {
                "namespaceId": namespace,
                "skillName": artifact.name,
                "version": artifact.version,
                "updateLatestLabel": "true",
            },
        )
    else:
        api.post_form(
            "/v3/admin/ai/skills/submit",
            {
                "namespaceId": namespace,
                "skillName": artifact.name,
                "version": artifact.version,
            },
        )
        wait_for_skill_publish(api, namespace, artifact.name, artifact.version)
    ensure_label(api, namespace, artifact.name, label, artifact.version)
    return {"asset": "skill", "name": artifact.name, "result": "published"}


def wait_for_skill_publish(
    api: NacosApi,
    namespace: str,
    skill_name: str,
    version: str,
) -> None:
    deadline = time.monotonic() + 30
    last_status = "unknown"
    while time.monotonic() < deadline:
        detail = api.get(
            "/v3/admin/ai/skills",
            {"namespaceId": namespace, "skillName": skill_name},
        )
        last_status = skill_status(detail, version)
        if last_status == "online":
            return
        if last_status in {"pipeline_rejected", "rejected", "failed"}:
            raise AssetError(f"Skill pipeline rejected {skill_name}@{version}")
        if last_status == "reviewing":
            try:
                api.post_form(
                    "/v3/admin/ai/skills/publish",
                    {
                        "namespaceId": namespace,
                        "skillName": skill_name,
                        "version": version,
                        "updateLatestLabel": "true",
                    },
                )
            except NacosApiError:
                pass
        time.sleep(1)
    raise AssetError(
        f"Skill {skill_name}@{version} did not publish within 30s; last status={last_status}"
    )


def skill_status(detail: Any, version: str) -> str:
    if not isinstance(detail, dict):
        return "missing"
    for item in detail.get("versions") or []:
        if isinstance(item, dict) and str(item.get("version")) == version:
            return str(item.get("status") or "").lower()
    return "missing"


def ensure_label(
    api: NacosApi,
    namespace: str,
    skill_name: str,
    label: str,
    version: str,
) -> None:
    if not label:
        return
    detail = api.get(
        "/v3/admin/ai/skills",
        {"namespaceId": namespace, "skillName": skill_name},
    )
    labels = detail.get("labels") if isinstance(detail, dict) else {}
    if isinstance(labels, dict) and labels.get(label) == version:
        return
    api.put_form(
        "/v3/admin/ai/skills/labels",
        {
            "namespaceId": namespace,
            "skillName": skill_name,
            "labels": json.dumps({label: version}, separators=(",", ":")),
        },
    )


def publish_prompt(
    api: NacosApi,
    namespace: str,
    prompt: dict[str, Any],
) -> dict[str, Any]:
    detail = api.get(
        "/v3/admin/ai/prompt/detail",
        {
            "namespaceId": namespace,
            "promptKey": prompt["key"],
            "version": prompt["version"],
        },
    )
    if isinstance(detail, dict):
        remote_template = normalize_newlines(str(detail.get("template") or ""))
        if remote_template != prompt["template"]:
            raise AssetError(
                f"Refusing to overwrite immutable prompt {prompt['key']}@{prompt['version']}: "
                "bump the manifest version"
            )
        result = "unchanged"
    else:
        api.post_form(
            "/v3/admin/ai/prompt",
            {
                "namespaceId": namespace,
                "promptKey": prompt["key"],
                "version": prompt["version"],
                "template": prompt["template"],
                "commitMsg": "MoonAgent asset pipeline",
                "description": "MoonAgent global system prompt",
            },
        )
        result = "published"
    api.put_form(
        "/v3/admin/ai/prompt/label",
        {
            "namespaceId": namespace,
            "promptKey": prompt["key"],
            "label": prompt["label"],
            "version": prompt["version"],
        },
    )
    return {"asset": "prompt", "name": prompt["key"], "result": result}


def publish_mcp(api: NacosApi, namespace: str, server: dict[str, Any]) -> dict[str, Any]:
    payload = build_mcp_payload(server, namespace)
    remote = api.get(
        "/v3/admin/ai/mcp",
        {
            "namespaceId": namespace,
            "mcpName": payload["name"],
            "version": payload["version"],
        },
    )
    if isinstance(remote, dict):
        assert_mcp_matches(remote, payload)
        return {"asset": "mcp", "name": payload["name"], "result": "unchanged"}

    api.post_form(
        "/v3/admin/ai/mcp",
        {
            "namespaceId": namespace,
            "serverSpecification": json.dumps(payload["serverSpecification"]),
            "toolSpecification": json.dumps(payload["toolSpecification"]),
            "endpointSpecification": json.dumps(payload["endpointSpecification"])
            if payload["endpointSpecification"]
            else None,
        },
    )
    return {"asset": "mcp", "name": payload["name"], "result": "published"}


def assert_mcp_matches(remote: dict[str, Any], expected: dict[str, Any]) -> None:
    remote_name = str(remote.get("name") or "")
    if remote_name != expected["name"]:
        raise AssetError(
            f"MCP name mismatch: Nacos={remote_name}, expected={expected['name']}"
        )
    remote_version = (
        (remote.get("versionDetail") or {}).get("version")
        if isinstance(remote.get("versionDetail"), dict)
        else None
    )
    if str(remote_version or remote.get("version") or "") != expected["version"]:
        raise AssetError(
            f"MCP version mismatch for {expected['name']}: "
            f"Nacos={remote_version}, expected={expected['version']}"
        )
    remote_tools = {
        str(tool.get("name"))
        for tool in ((remote.get("toolSpec") or {}).get("tools") or [])
        if isinstance(tool, dict)
    }
    expected_tools = {
        str(tool.get("name"))
        for tool in expected["toolSpecification"]["tools"]
        if isinstance(tool, dict)
    }
    if remote_tools != expected_tools:
        raise AssetError(
            f"MCP tools mismatch for {expected['name']}: "
            f"Nacos={sorted(remote_tools)}, expected={sorted(expected_tools)}"
        )


def main() -> int:
    args = parse_args()
    try:
        manifest_path = args.manifest if args.manifest.is_absolute() else REPO_ROOT / args.manifest
        manifest, artifacts = load_manifest(manifest_path)
        prompt = load_global_prompt(manifest_path, manifest)
        mcp_servers = load_mcp_servers(manifest_path, manifest)
        namespace = args.namespace or str(manifest.get("namespace") or "public")
        skill_label = (
            args.label
            or str(prompt.get("label") or "")
            or "stable"
        )

        if args.command == "verify" and args.offline:
            report = offline_verify(manifest, artifacts, prompt, mcp_servers)
        else:
            server_address = args.server_addr or str(manifest.get("serverAddress") or "")
            if not server_address:
                raise AssetError(
                    "Nacos address is required; pass --server-addr or set NACOS_ADDR"
                )
            api = NacosApi(
                server_address=server_address,
                timeout=args.timeout,
                access_token=args.token,
                username=args.username,
                password=args.password,
            )
            if args.command == "verify":
                report = online_verify(
                    api,
                    namespace,
                    skill_label,
                    artifacts,
                    prompt,
                    mcp_servers,
                )
            else:
                report = publish_assets(
                    api,
                    namespace,
                    skill_label,
                    artifacts,
                    prompt,
                    mcp_servers,
                    args.force,
                )
        if args.json:
            print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
        else:
            print_summary(report)
        return 0
    except AssetError as error:
        print(f"asset-pipeline: {error}", file=sys.stderr)
        return 1


def print_summary(report: dict[str, Any]) -> None:
    print(
        f"[{report.get('mode')}] namespace={report.get('namespace')} "
        f"skills={len(report.get('skills') or [])} "
        f"mcpServers={len(report.get('mcpServers') or [])}"
    )
    for skill in report.get("skills") or []:
        print(
            f"  skill {skill.get('name')}@{skill.get('version')} "
            f"{skill.get('sha256', '')}"
        )
    for action in report.get("actions") or []:
        print(
            f"  {action.get('asset')} {action.get('name')} "
            f"{action.get('result')}"
        )


if __name__ == "__main__":
    raise SystemExit(main())
