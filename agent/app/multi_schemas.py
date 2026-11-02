"""The signed multi-agent contract is independent of the single-agent schema."""

import json
import math
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

AgentId = Literal["material", "operation", "routing", "workorder"]
ToolName = Literal["get_material", "get_operations", "get_material_routing", "get_work_order"]
Status = Literal[
    "SUCCESS", "PARTIAL_SUCCESS", "NEEDS_INPUT", "TOOL_ERROR", "UNSUPPORTED", "TIMEOUT"
]
ROLE_TOOLS: dict[str, str] = {
    "material": "get_material",
    "operation": "get_operations",
    "routing": "get_material_routing",
    "workorder": "get_work_order",
}


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, populate_by_name=True)


class UserMessage(StrictModel):
    role: Literal["user"] = "user"
    content: str = Field(min_length=1, max_length=1000)


class AuthorizedContext(StrictModel):
    subject: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_.:-]+$")
    scopes: list[Literal["public", "restricted"]] = Field(default_factory=list, max_length=2)
    allowedTools: list[ToolName] = Field(default_factory=list, max_length=4)
    issuedAt: int = Field(ge=0)
    expiresAt: int = Field(ge=0)

    @model_validator(mode="after")
    def unique_grants(self):
        if len(set(self.scopes)) != len(self.scopes) or len(set(self.allowedTools)) != len(
            self.allowedTools
        ):
            raise ValueError("Duplicate authorization grants")
        if not self.issuedAt < self.expiresAt <= self.issuedAt + 60:
            raise ValueError("Invalid authorization lifetime")
        return self


def _bounded_json(value: Any, depth: int = 0):
    if depth > 8:
        raise ValueError("Tool data is nested too deeply")
    if isinstance(value, dict):
        if len(value) > 200 or any(not isinstance(key, str) or len(key) > 128 for key in value):
            raise ValueError("Invalid tool object")
        for item in value.values():
            _bounded_json(item, depth + 1)
    elif isinstance(value, list):
        if len(value) > 200:
            raise ValueError("Tool collection is too large")
        for item in value:
            _bounded_json(item, depth + 1)
    elif isinstance(value, str):
        if len(value) > 12000:
            raise ValueError("Tool string is too large")
    elif value is not None and type(value) not in (bool, int, float):
        raise ValueError("Tool values must be JSON")
    elif isinstance(value, float) and not math.isfinite(value):
        raise ValueError("Tool numbers must be finite")


class ProvidedToolResult(StrictModel):
    tool: ToolName
    parameters: dict[str, Any]
    result: dict[str, Any]

    @field_validator("parameters", "result")
    @classmethod
    def bounded_tool_data(cls, value):
        _bounded_json(value)
        if len(json.dumps(value, ensure_ascii=False)) > 24000:
            raise ValueError("Tool data is too large")
        return value


class MultiAgentRequest(StrictModel):
    requestId: str = Field(min_length=36, max_length=36)
    question: str = Field(min_length=1, max_length=1000)
    messages: list[UserMessage] = Field(default_factory=list, max_length=4)
    requestedAgents: list[AgentId] = Field(min_length=1, max_length=4)
    queryMode: Literal["independent", "operation_chain"] = "independent"
    authorizedContext: AuthorizedContext
    providedToolResults: list[ProvidedToolResult] = Field(default_factory=list, max_length=4)

    @field_validator("requestId")
    @classmethod
    def canonical_uuid(cls, value):
        if str(UUID(value)) != value:
            raise ValueError("Noncanonical request UUID")
        return value

    @model_validator(mode="after")
    def consistent_role_grants(self):
        agents = self.requestedAgents
        allowed = self.authorizedContext.allowedTools
        supplied = [item.tool for item in self.providedToolResults]
        if len(set(agents)) != len(agents) or len(set(supplied)) != len(supplied):
            raise ValueError("Duplicate role or supplied tool")
        if self.queryMode == "operation_chain" and "operation" not in agents:
            raise ValueError("Operation dependency requires the operation role")
        if set(allowed) != {ROLE_TOOLS[role] for role in agents} or not set(supplied) <= set(
            allowed
        ):
            raise ValueError("Role, permission and supplied facts disagree")
        return self


class ToolCall(StrictModel):
    name: ToolName
    status: Literal["SUCCESS", "NO_RESULTS", "DENIED", "ERROR"]
    elapsedMs: int = Field(ge=0)


class AgentResult(StrictModel):
    agentId: AgentId
    status: Status
    answer: str = Field(max_length=4000)
    toolCalls: list[ToolCall] = Field(default_factory=list, max_length=1)
    executedNodes: list[str] = Field(default_factory=list, max_length=3)
    modelAttempted: bool = False
    modelConnected: bool = False
    fallbackReason: str | None = None


class Handoff(StrictModel):
    from_: Literal["coordinator", "material", "operation", "routing", "workorder"] = Field(
        alias="from"
    )
    to: Literal["coordinator", "material", "operation", "routing", "workorder"]
    status: Status


class MultiAgentResponse(StrictModel):
    requestId: str
    status: Status
    mode: Literal["multi-agent"] = "multi-agent"
    answer: str = Field(max_length=12000)
    agentFramework: Literal["LANGGRAPH_MULTI_AGENT"] = "LANGGRAPH_MULTI_AGENT"
    agentExecuted: bool = False
    executedAgents: list[AgentId] = Field(default_factory=list, max_length=4)
    agentResults: list[AgentResult] = Field(default_factory=list, max_length=4)
    handoffs: list[Handoff] = Field(default_factory=list, max_length=8)
    llmConfigured: bool = False
    modelAttempted: bool = False
    modelConnected: bool = False
    fallbackReason: str | None = None
    elapsedMs: int = Field(default=0, ge=0)


class Evidence(StrictModel):
    evidenceId: str = Field(min_length=1, max_length=120)
    quote: str = Field(min_length=1, max_length=900)


class EvidenceSelection(StrictModel):
    evidence: list[Evidence] = Field(min_length=1, max_length=3)
