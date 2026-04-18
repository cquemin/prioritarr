"""Augment openapi.json with the v2 surface declared in Spec C.

Reads the committed openapi.json (which currently describes only the v1
webhook + health surface), adds:

- components.securitySchemes.api_key
- ProblemDetail component + a shared 4xx/5xx response block
- Every /api/v2/* operation with `security: [{ api_key: [] }]`
- /api/v2/events with `text/event-stream` response

Writes the result back to openapi.json with sorted keys for deterministic
diffs. Run: `python scripts/augment_openapi_v2.py`.
"""
from __future__ import annotations

import json
from pathlib import Path

SPEC_PATH = Path(__file__).resolve().parent.parent / "openapi.json"


PROBLEM_SCHEMA = {
    "type": "object",
    "properties": {
        "type": {"type": "string"},
        "title": {"type": "string"},
        "status": {"type": "integer"},
        "detail": {"type": "string"},
        "instance": {"type": "string", "nullable": True},
    },
    "required": ["type", "title", "status", "detail"],
    "title": "ProblemDetail",
}

PROBLEM_RESPONSE = lambda status: {
    "description": {
        "400": "Bad request", "401": "Unauthorized", "403": "Forbidden",
        "404": "Not found", "422": "Validation", "500": "Internal", "502": "Upstream unreachable",
    }.get(status, "Error"),
    "content": {"application/problem+json": {"schema": {"$ref": "#/components/schemas/ProblemDetail"}}},
}

PAGINATED_ENVELOPE = {
    "type": "object",
    "properties": {
        "records": {"type": "array", "items": {"type": "object"}},
        "totalRecords": {"type": "integer"},
        "offset": {"type": "integer"},
        "limit": {"type": "integer"},
    },
    "required": ["records", "totalRecords", "offset", "limit"],
    "title": "PaginatedEnvelope",
}

ACTION_RESULT = {
    "type": "object",
    "properties": {
        "ok": {"type": "boolean"},
        "dryRun": {"type": "boolean"},
        "message": {"type": "string", "nullable": True},
    },
    "required": ["ok"],
    "title": "ActionResult",
}


def page_params():
    return [
        {"name": "offset", "in": "query", "schema": {"type": "integer", "default": 0, "minimum": 0}},
        {"name": "limit", "in": "query", "schema": {"type": "integer", "default": 50, "minimum": 1, "maximum": 1000}},
        {"name": "sort", "in": "query", "schema": {"type": "string"}},
        {"name": "sort_dir", "in": "query", "schema": {"type": "string", "enum": ["asc", "desc"]}},
    ]


def v2_op(*, summary, responses_200_schema, extra_params=None, with_pagination=False, method="get"):
    params = list(extra_params or [])
    if with_pagination:
        params += page_params()
    errors = {
        "401": PROBLEM_RESPONSE("401"),
        "422": PROBLEM_RESPONSE("422"),
        "500": PROBLEM_RESPONSE("500"),
    }
    op = {
        "summary": summary,
        "security": [{"api_key": []}],
        "parameters": params,
        "responses": {
            "200": {
                "description": "OK",
                "content": {"application/json": {"schema": responses_200_schema}},
            },
            **errors,
        },
    }
    if not params:
        del op["parameters"]
    return {method: op}


def main():
    spec = json.loads(SPEC_PATH.read_text())

    # components
    components = spec.setdefault("components", {})
    schemas = components.setdefault("schemas", {})
    schemas["ProblemDetail"] = PROBLEM_SCHEMA
    schemas["PaginatedEnvelope"] = PAGINATED_ENVELOPE
    schemas["ActionResult"] = ACTION_RESULT
    components.setdefault("securitySchemes", {})["api_key"] = {
        "type": "apiKey", "in": "header", "name": "X-Api-Key",
    }

    paths = spec.setdefault("paths", {})

    # ---- Reads ----
    paths["/api/v2/series"] = v2_op(
        summary="List managed series (paginated)",
        responses_200_schema={"$ref": "#/components/schemas/PaginatedEnvelope"},
        with_pagination=True,
    )
    paths["/api/v2/series/{id}"] = v2_op(
        summary="Get series detail",
        responses_200_schema={"type": "object", "title": "SeriesDetail"},
        extra_params=[{"name": "id", "in": "path", "required": True, "schema": {"type": "integer"}}],
    )
    paths["/api/v2/downloads"] = v2_op(
        summary="List managed downloads (paginated; filter by client)",
        responses_200_schema={"$ref": "#/components/schemas/PaginatedEnvelope"},
        extra_params=[{"name": "client", "in": "query", "schema": {"type": "string", "enum": ["qbit", "sab"]}}],
        with_pagination=True,
    )
    paths["/api/v2/downloads/{client}/{clientId}"] = {
        **v2_op(
            summary="Get managed download detail",
            responses_200_schema={"type": "object", "title": "ManagedDownload"},
            extra_params=[
                {"name": "client", "in": "path", "required": True, "schema": {"type": "string"}},
                {"name": "clientId", "in": "path", "required": True, "schema": {"type": "string"}},
            ],
        ),
        "delete": {
            "summary": "Untrack a managed download",
            "security": [{"api_key": []}],
            "parameters": [
                {"name": "client", "in": "path", "required": True, "schema": {"type": "string"}},
                {"name": "clientId", "in": "path", "required": True, "schema": {"type": "string"}},
            ],
            "responses": {
                "200": {"description": "Untracked", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ActionResult"}}}},
                "401": PROBLEM_RESPONSE("401"),
                "404": PROBLEM_RESPONSE("404"),
                "500": PROBLEM_RESPONSE("500"),
            },
        },
    }
    paths["/api/v2/audit"] = v2_op(
        summary="List audit log entries (paginated; filter by series_id/action/since)",
        responses_200_schema={"$ref": "#/components/schemas/PaginatedEnvelope"},
        extra_params=[
            {"name": "series_id", "in": "query", "schema": {"type": "integer"}},
            {"name": "action", "in": "query", "schema": {"type": "string"}},
            {"name": "since", "in": "query", "schema": {"type": "string", "format": "date-time"}},
        ],
        with_pagination=True,
    )
    paths["/api/v2/settings"] = v2_op(
        summary="Runtime settings (secrets redacted)",
        responses_200_schema={"type": "object", "title": "SettingsRedacted"},
    )
    paths["/api/v2/mappings"] = v2_op(
        summary="Plex↔Sonarr mapping snapshot",
        responses_200_schema={"type": "object", "title": "MappingSnapshot"},
    )
    paths["/api/v2/stats"] = v2_op(
        summary="Aggregated counters — dashboard-widget friendly",
        responses_200_schema={"type": "object", "title": "StatsResponse"},
    )

    # ---- Actions ----
    def action(summary, extra_params=None):
        return {
            "post": {
                "summary": summary,
                "security": [{"api_key": []}],
                "parameters": list(extra_params or []),
                "responses": {
                    "200": {"description": "OK", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ActionResult"}}}},
                    "401": PROBLEM_RESPONSE("401"),
                    "404": PROBLEM_RESPONSE("404"),
                    "422": PROBLEM_RESPONSE("422"),
                    "500": PROBLEM_RESPONSE("500"),
                    "502": PROBLEM_RESPONSE("502"),
                },
            },
        }

    paths["/api/v2/series/{id}/recompute"] = action(
        "Invalidate cache and recompute priority",
        [{"name": "id", "in": "path", "required": True, "schema": {"type": "integer"}}],
    )
    paths["/api/v2/mappings/refresh"] = action("Trigger a mapping refresh")
    paths["/api/v2/downloads/{client}/{clientId}/actions/{action}"] = action(
        "Pause / resume / boost / demote a tracked download",
        [
            {"name": "client", "in": "path", "required": True, "schema": {"type": "string"}},
            {"name": "clientId", "in": "path", "required": True, "schema": {"type": "string"}},
            {"name": "action", "in": "path", "required": True, "schema": {"type": "string", "enum": ["pause", "resume", "boost", "demote"]}},
        ],
    )

    # ---- SSE ----
    paths["/api/v2/events"] = {
        "get": {
            "summary": "Server-Sent Events stream",
            "security": [{"api_key": []}],
            "parameters": [
                {"name": "Last-Event-ID", "in": "header", "schema": {"type": "integer"}},
                {"name": "lastEventId", "in": "query", "schema": {"type": "integer"}},
            ],
            "responses": {
                "200": {
                    "description": "Event stream",
                    "content": {"text/event-stream": {"schema": {"type": "string"}}},
                },
                "401": PROBLEM_RESPONSE("401"),
            },
        },
    }

    SPEC_PATH.write_text(json.dumps(spec, sort_keys=True, indent=2) + "\n")
    print(f"wrote {SPEC_PATH} ({SPEC_PATH.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
