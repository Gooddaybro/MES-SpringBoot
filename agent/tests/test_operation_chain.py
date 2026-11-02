"""Dependency execution tests over explicit signed test facts, with no database or paid model."""

import asyncio

import pytest
from test_multi_agent import payload, settings

from app.multi_graph import MultiAgentWorkflow
from app.multi_schemas import MultiAgentRequest


def chain(*, operation_status="SUCCESS", operations=None):
    data = payload()
    data["queryMode"] = "operation_chain"
    data["question"] = "查询包含老化测试工序的物料和工单"
    if operations is None:
        operations = [{"id": "actual-op", "code": "AGE-1", "name": "老化测试"}]
    for fact in data["providedToolResults"]:
        fact["parameters"] = {"operationId": "actual-op"}
        if fact["tool"] == "get_operations":
            fact["result"] = {
                "status": operation_status,
                "operations": operations,
                "hasMore": False,
            }
    return MultiAgentRequest.model_validate(data)


async def test_operation_worker_finishes_before_any_dependent_worker_starts():
    workflow = MultiAgentWorkflow(settings())
    visits = []
    for role, worker in workflow.workers.items():
        original = worker.run

        async def run(state, *, _role=role, _original=original):
            if _role != "operation":
                assert "operation:finished" in visits
            visits.append(_role + ":started")
            result = await _original(state)
            visits.append(_role + ":finished")
            return result

        worker.run = run
    try:
        response = await workflow.run(chain())
        assert response.status == "SUCCESS"
        assert response.executedAgents[0] == "operation"
        assert set(response.executedAgents) == set(workflow.workers)
        assert len(response.handoffs) == 8
    finally:
        await workflow.close()


@pytest.mark.parametrize(
    "status,operations",
    [
        ("NO_OPERATION", []),
        ("AMBIGUOUS", [{"id": "a"}, {"id": "b"}]),
        ("SUCCESS", [{"id": "a"}, {"id": "b"}]),
        ("DATA_UNAVAILABLE", []),
    ],
)
async def test_empty_ambiguous_or_failed_operation_never_runs_dependents(status, operations):
    workflow = MultiAgentWorkflow(settings())
    for role, worker in workflow.workers.items():
        if role != "operation":
            async def forbidden(_):
                pytest.fail("An unresolved dependency was executed")
            worker.run = forbidden
    try:
        response = await workflow.run(chain(operation_status=status, operations=operations))
        assert response.executedAgents == ["operation"]
        assert response.status in {"NEEDS_INPUT", "TOOL_ERROR"}
        assert "material-private-fact" not in response.answer
    finally:
        await workflow.close()


async def test_signed_but_mismatched_dependency_identity_is_not_executed():
    request = chain()
    request.providedToolResults[0].parameters["operationId"] = "unrelated-operation"
    workflow = MultiAgentWorkflow(settings())
    try:
        response = await workflow.run(request)
        assert response.status == "TOOL_ERROR"
        assert response.executedAgents == ["operation"]
        assert response.fallbackReason == "DEPENDENCY_MISMATCH"
        assert "material-private-fact" not in response.answer
    finally:
        await workflow.close()


async def test_resolving_operation_is_also_bounded_by_the_total_request_deadline():
    workflow = MultiAgentWorkflow(settings(request_timeout_seconds=1))
    original = workflow.workers["operation"].run

    async def slow(state):
        await asyncio.sleep(1.1)
        return await original(state)

    workflow.workers["operation"].run = slow
    try:
        response = await workflow.run(chain())
        assert response.status == "TIMEOUT"
        assert response.executedAgents == ["operation"]
        assert len(response.handoffs) == 2
    finally:
        await workflow.close()
