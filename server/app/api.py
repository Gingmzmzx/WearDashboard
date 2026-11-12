"""REST API used by the agent plugin / DeepSeek harness."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status

from .auth import require_api_token
from .schemas import (
    AgentOut,
    AgentStatusIn,
    CreateRequestIn,
    DeviceCreateIn,
    DeviceIssuedOut,
    DeviceOut,
    RequestDetailOut,
    StateOut,
    TaskIn,
    TaskOut,
)
from .service import GatewayService

router = APIRouter()


def _service(request: Request) -> GatewayService:
    return request.app.state.service


@router.get("/healthz", tags=["system"])
async def healthz(request: Request) -> dict[str, object]:
    service: GatewayService = request.app.state.service
    hub = request.app.state.hub
    state = await service.state(hub.count())
    return {
        "ok": True,
        "version": "1.0.0",
        "seq": state["seq"],
        "connections": state["connections"],
        "pending": state["pending_count"],
    }


@router.post(
    "/api/v1/requests",
    response_model=RequestDetailOut,
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(require_api_token)],
    tags=["requests"],
    summary="Create a permission request or question",
)
async def create_request(payload: CreateRequestIn, request: Request) -> RequestDetailOut:
    service = _service(request)
    return await service.create_request(payload)


@router.get(
    "/api/v1/requests/{request_id}",
    response_model=RequestDetailOut,
    dependencies=[Depends(require_api_token)],
    tags=["requests"],
)
async def get_request(request_id: str, request: Request) -> RequestDetailOut:
    detail = await _service(request).get_request(request_id)
    if detail is None:
        raise HTTPException(status_code=404, detail="Request not found")
    return detail


@router.get(
    "/api/v1/requests",
    response_model=list[RequestDetailOut],
    dependencies=[Depends(require_api_token)],
    tags=["requests"],
)
async def list_requests(
    request: Request,
    request_status: str | None = Query(
        default=None,
        alias="status",
        pattern="^(pending|answered|expired|cancelled)$",
    ),
    limit: int = Query(default=50, ge=1, le=200),
) -> list[RequestDetailOut]:
    return await _service(request).list_requests(request_status, limit)


@router.get(
    "/api/v1/requests/{request_id}/wait",
    response_model=RequestDetailOut,
    dependencies=[Depends(require_api_token)],
    tags=["requests"],
    summary="Long-poll until the request is resolved or the timeout elapses",
)
async def wait_request(
    request_id: str,
    request: Request,
    timeout: float = Query(default=30.0, ge=0.0, le=60.0),
) -> RequestDetailOut:
    detail = await _service(request).wait_for_resolution(request_id, timeout)
    if detail is None:
        raise HTTPException(status_code=404, detail="Request not found")
    return detail


@router.post(
    "/api/v1/requests/{request_id}/cancel",
    response_model=RequestDetailOut,
    dependencies=[Depends(require_api_token)],
    tags=["requests"],
)
async def cancel_request(request_id: str, request: Request) -> RequestDetailOut:
    outcome, detail = await _service(request).cancel_request(request_id)
    if outcome == "not_found":
        raise HTTPException(status_code=404, detail="Request not found")
    if outcome == "already_resolved":
        raise HTTPException(status_code=409, detail="Request already resolved")
    assert detail is not None
    return detail


@router.post(
    "/api/v1/agents/{agent_id}/status",
    response_model=AgentOut,
    dependencies=[Depends(require_api_token)],
    tags=["agents"],
)
async def update_agent_status(
    agent_id: str, payload: AgentStatusIn, request: Request
) -> AgentOut:
    return await _service(request).update_agent_status(agent_id, payload)


@router.post(
    "/api/v1/tasks",
    response_model=TaskOut,
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(require_api_token)],
    tags=["tasks"],
)
async def complete_task(payload: TaskIn, request: Request) -> TaskOut:
    return await _service(request).complete_task(payload)


@router.get(
    "/api/v1/state",
    response_model=StateOut,
    dependencies=[Depends(require_api_token)],
    tags=["system"],
)
async def state(request: Request) -> StateOut:
    service: GatewayService = request.app.state.service
    hub = request.app.state.hub
    return StateOut.model_validate(await service.state(hub.count()))


@router.post(
    "/api/v1/devices",
    response_model=DeviceIssuedOut,
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(require_api_token)],
    tags=["devices"],
    summary="Issue a device token. The raw token is returned only once.",
)
async def issue_device(payload: DeviceCreateIn, request: Request) -> DeviceIssuedOut:
    return await _service(request).issue_device(payload.name)


@router.get(
    "/api/v1/devices",
    response_model=list[DeviceOut],
    dependencies=[Depends(require_api_token)],
    tags=["devices"],
)
async def list_devices(request: Request) -> list[DeviceOut]:
    return await _service(request).list_devices()


@router.delete(
    "/api/v1/devices/{device_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    dependencies=[Depends(require_api_token)],
    tags=["devices"],
)
async def revoke_device(device_id: str, request: Request) -> Response:
    ok = await _service(request).revoke_device(device_id)
    if not ok:
        raise HTTPException(status_code=404, detail="Device not found")
    return Response(status_code=status.HTTP_204_NO_CONTENT)
