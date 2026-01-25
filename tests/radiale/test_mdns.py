import pytest
import asyncio
from unittest.mock import patch, MagicMock, AsyncMock, ANY

from zeroconf import ServiceStateChange, IPVersion
# AsyncServiceInfo is directly available from zeroconf.asyncio
from zeroconf.asyncio import AsyncServiceInfo

from radiale.mdns import (
    mdns_state_change,
    MDNS
)
from radiale.pod import OutgoingQ # mdns_state_change calls out.write_msg

# --- Tests for mdns_state_change ---

@pytest.mark.asyncio
async def test_mdns_state_change_added():
    mock_out_q = AsyncMock(spec=OutgoingQ)
    mock_out_q.write_msg = MagicMock() # write_msg itself is synchronous

    service_type = "_test._tcp.local."
    name = "MyTestService._test._tcp.local."
    state_change = ServiceStateChange.Added

    # The actual function signature is: mdns_state_change(o, id, zeroconf, service_type, name, state_change)
    await mdns_state_change(mock_out_q, "test_id", None, service_type, name, state_change)

    mock_out_q.write_msg.assert_called_once_with(
        id="test_id",
        data={
            "service-name": "MyTestService", # Name should be stripped
            "service-type": service_type,
            "state-change": "added", # Mapped state
        }
    )

@pytest.mark.asyncio
async def test_mdns_state_change_removed():
    mock_out_q = AsyncMock(spec=OutgoingQ)
    mock_out_q.write_msg = MagicMock()

    service_type = "_http._tcp.local."
    # The implementation uses name.split('.', 1)[0] so only the first part before '.' is kept
    name = "AnotherService._http._tcp.local."
    state_change = ServiceStateChange.Removed

    # The actual function signature is: mdns_state_change(o, id, zeroconf, service_type, name, state_change)
    await mdns_state_change(mock_out_q, "id_removed", None, service_type, name, state_change)

    mock_out_q.write_msg.assert_called_once_with(
        id="id_removed",
        data={
            "service-name": "AnotherService",
            "service-type": service_type,
            "state-change": "removed",
        }
    )

# --- Tests for MDNS class ---

@pytest.fixture
def mdns_instance():
    return MDNS()

@pytest.mark.asyncio
@patch('radiale.mdns.AsyncZeroconf') # Patching AsyncZeroconf from zeroconf.asyncio
async def test_mdns_start(MockAsyncZeroconf, mdns_instance):
    mock_aiozc_instance = AsyncMock()
    MockAsyncZeroconf.return_value = mock_aiozc_instance
    mock_out_q = AsyncMock(spec=OutgoingQ)

    returned_instance = await mdns_instance.start(mock_out_q)

    MockAsyncZeroconf.assert_called_once_with(ip_version=IPVersion.V4Only)
    assert mdns_instance.aiozc == mock_aiozc_instance
    assert mdns_instance.out == mock_out_q
    assert returned_instance == mdns_instance

@pytest.mark.asyncio
async def test_mdns_get_info(mdns_instance):
    # Setup a mocked aiozc
    mdns_instance.aiozc = AsyncMock()
    # aiozc.zeroconf is the actual zeroconf instance used by AsyncServiceInfo
    mdns_instance.aiozc.zeroconf = MagicMock()

    service_type = "_test._tcp.local."
    service_name = "MyDevice" # Usually the full name is constructed inside get_info
    full_service_name = f"{service_name}.{service_type}"

    # Mock AsyncServiceInfo constructor and its async_request method
    mock_service_info_instance = AsyncMock(spec=AsyncServiceInfo)

    with patch('radiale.mdns.AsyncServiceInfo', return_value=mock_service_info_instance) as MockAsyncServiceInfoCls:
        info = await mdns_instance.get_info(service_type, service_name)

        MockAsyncServiceInfoCls.assert_called_once_with(service_type, full_service_name)
        # The actual code uses positional arg, not keyword arg for timeout
        mock_service_info_instance.async_request.assert_called_once_with(mdns_instance.aiozc.zeroconf, 3000)
        assert info == mock_service_info_instance

@pytest.mark.asyncio
async def test_mdns_info_success(mdns_instance):
    mdns_instance.out = AsyncMock(spec=OutgoingQ)
    mdns_instance.out.write_msg = MagicMock()

    mock_service_info = MagicMock(spec=AsyncServiceInfo) # Use MagicMock for sync attributes
    mock_service_info.parsed_scoped_addresses = MagicMock(return_value=["127.0.0.1"])
    mock_service_info.port = 1234
    mock_service_info.weight = 0
    mock_service_info.priority = 0
    mock_service_info.server = "test-server.local."
    mock_service_info.properties = {b"key1": b"value1", b"key2": b"value2"}

    mdns_instance.get_info = AsyncMock(return_value=mock_service_info)

    opts = {'service-type': '_stype._tcp.local.', 'service-name': 'sname'}
    await mdns_instance.info(id="test_id1", opts=opts)

    mdns_instance.get_info.assert_called_once_with(opts['service-type'], opts['service-name'])
    # The actual implementation uses different structure - check the source
    # It builds: {**opts, **{addresses, weight, priority, server, properties}}
    expected_data = {
        **opts,
        "addresses": [["127.0.0.1", 1234]],  # [add, port] pairs
        "weight": 0,
        "priority": 0,
        "server": "test-server.local.",
        "properties": {"key1": "value1", "key2": "value2"}
    }
    mdns_instance.out.write_msg.assert_called_once_with(id="test_id1", data=expected_data)

@pytest.mark.asyncio
async def test_mdns_info_get_info_returns_none(mdns_instance):
    mdns_instance.out = AsyncMock(spec=OutgoingQ)
    mdns_instance.out.write_msg = MagicMock()
    mdns_instance.get_info = AsyncMock(return_value=None)

    opts = {'service-type': '_stype._tcp.local.', 'service-name': 'sname_fail'}
    await mdns_instance.info(id="test_id2", opts=opts)

    mdns_instance.get_info.assert_called_once_with(opts['service-type'], opts['service-name'])
    # The actual implementation returns data=None when info is None
    mdns_instance.out.write_msg.assert_called_once_with(id="test_id2", data=None)


@pytest.mark.asyncio
@patch('radiale.mdns.AsyncServiceBrowser')
@patch('radiale.mdns.asyncio.ensure_future') # To check how mdns_state_change is scheduled
async def test_mdns_listen_new_browser(mock_ensure_future, MockAsyncServiceBrowser, mdns_instance):
    # Setup MDNS instance with mocked aiozc and out
    mdns_instance.aiozc = AsyncMock()
    mdns_instance.aiozc.zeroconf = MagicMock() # Needed by AsyncServiceBrowser
    mdns_instance.out = AsyncMock(spec=OutgoingQ)
    mdns_instance.browsers = {}  # Initialize browsers dict

    opts = {'service-type': '_newtype._tcp.local.'}
    test_id = "listen_id_1"

    await mdns_instance.listen(id=test_id, opts=opts)

    # Assert AsyncServiceBrowser was called
    MockAsyncServiceBrowser.assert_called_once_with(
        mdns_instance.aiozc.zeroconf,
        opts['service-type'],
        handlers=[ANY]
    )

    # Check that the handler passed to AsyncServiceBrowser, when called,
    # eventually calls ensure_future with mdns_state_change.
    # Get the actual handler function passed to AsyncServiceBrowser
    call_kwargs = MockAsyncServiceBrowser.call_args.kwargs
    if 'handlers' in call_kwargs:
        passed_handlers = call_kwargs['handlers']
    else:
        args = MockAsyncServiceBrowser.call_args.args
        passed_handlers = args[2] if len(args) > 2 else MockAsyncServiceBrowser.call_args.kwargs.get('handlers', [])
    
    handler_fn_wrapper = passed_handlers[0]

    # Simulate zeroconf calling this handler with keyword arguments
    mock_zc_instance = MagicMock()
    stype_arg = "_newtype._tcp.local."
    sname_arg = "someservice._newtype._tcp.local."
    sstate_arg = ServiceStateChange.Added

    handler_fn_wrapper(zeroconf=mock_zc_instance, service_type=stype_arg, name=sname_arg, state_change=sstate_arg)

    # Now check that ensure_future was called
    mock_ensure_future.assert_called_once()

    assert opts['service-type'] in mdns_instance.browsers
    assert mdns_instance.browsers[opts['service-type']] == MockAsyncServiceBrowser.return_value

@pytest.mark.asyncio
@patch('radiale.mdns.AsyncServiceBrowser')
async def test_mdns_listen_existing_browser(MockAsyncServiceBrowser, mdns_instance):
    service_type = '_existing._tcp.local.'
    mdns_instance.browsers = {service_type: MagicMock()}  # Pre-populate

    mdns_instance.aiozc = AsyncMock() # Still need aiozc for the check
    mdns_instance.out = AsyncMock(spec=OutgoingQ)

    opts = {'service-type': service_type}
    await mdns_instance.listen(id="listen_id_2", opts=opts)

    MockAsyncServiceBrowser.assert_not_called() # Should not create a new browser
    # Optionally, assert that a message is logged or some other behavior for existing browser.
    # Current code doesn't do anything specific, so not calling is the main check.
