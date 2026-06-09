import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch, ANY

from radiale.esphome import ESPHome, SERVICE_TYPE
from radiale.pod import OutgoingQ # For type hinting/spec
from radiale.mdns import MDNS    # For type hinting/spec

from aioesphomeapi.core import APIConnectionError, TimeoutAPIError
from aioesphomeapi.model import UserService

# --- Fixtures ---

@pytest.fixture
def mock_out_q():
    out_q = AsyncMock(spec=OutgoingQ)
    out_q.write_msg = MagicMock() # write_msg is synchronous
    return out_q

@pytest.fixture
def mock_mdns_service():
    return AsyncMock(spec=MDNS)

@pytest.fixture
def esphome_instance(mock_out_q, mock_mdns_service):
    # Patch eprint globally for tests in this module if it's too noisy
    with patch('radiale.pod.eprint', MagicMock()):
        instance = ESPHome(out=mock_out_q, id="esp_test_id", mdns=mock_mdns_service, service_name="MyESPHome")
        # Reset mocks that might be called during __init__ if any (none here)
        mock_out_q.reset_mock()
        mock_mdns_service.reset_mock()
        return instance

# --- Tests for ESPHome class ---

def test_esphome_init(esphome_instance, mock_out_q, mock_mdns_service):
    assert esphome_instance.out == mock_out_q
    assert esphome_instance.id == "esp_test_id"
    assert esphome_instance.mdns == mock_mdns_service
    assert esphome_instance.service_name == "MyESPHome"
    assert esphome_instance.cli is None
    assert esphome_instance.retries == 0
    assert esphome_instance.connected is False
    assert esphome_instance.connecting is False
    assert isinstance(esphome_instance.connecting_lock, asyncio.Lock)

@pytest.mark.asyncio
async def test_esphome_connected_state(esphome_instance, mock_out_q):
    await esphome_instance.connected_state(True)
    mock_out_q.write_msg.assert_called_once_with(
        id="esp_test_id",
        data={"service-name": "MyESPHome", "connected": True}
    )
    mock_out_q.reset_mock()
    await esphome_instance.connected_state(False)
    mock_out_q.write_msg.assert_called_once_with(
        id="esp_test_id",
        data={"service-name": "MyESPHome", "connected": False}
    )

@pytest.mark.asyncio
async def test_esphome_connect_success(esphome_instance, mock_mdns_service):
    mock_service_info = MagicMock()  # Use MagicMock not AsyncMock for sync methods
    mock_service_info.parsed_scoped_addresses.return_value = ["10.0.0.1"]
    mock_service_info.port = 6053
    mock_mdns_service.get_info = AsyncMock(return_value=mock_service_info)

    mock_apiclient_instance = AsyncMock()
    mock_apiclient_instance.connect = AsyncMock()

    with patch('radiale.esphome.aioesphomeapi.APIClient', return_value=mock_apiclient_instance) as MockAPIClientCls:
        await esphome_instance.connect()

        mock_mdns_service.get_info.assert_awaited_once_with(SERVICE_TYPE, "MyESPHome")
        MockAPIClientCls.assert_called_once_with("10.0.0.1", 6053, None) # password is None
        assert esphome_instance.cli == mock_apiclient_instance
        mock_apiclient_instance.connect.assert_awaited_once_with(on_stop=esphome_instance.on_disconnect, login=True)

@pytest.mark.asyncio
async def test_esphome_connect_failure_no_hosts(esphome_instance, mock_mdns_service):
    mock_service_info = MagicMock()  # Use MagicMock not AsyncMock for sync methods
    mock_service_info.parsed_scoped_addresses.return_value = [] # No hosts
    mock_mdns_service.get_info = AsyncMock(return_value=mock_service_info)

    with patch('radiale.esphome.aioesphomeapi.APIClient'):
        with pytest.raises(APIConnectionError): # As per code, it raises APIConnectionError directly
            await esphome_instance.connect()
    mock_mdns_service.get_info.assert_awaited_once_with(SERVICE_TYPE, "MyESPHome")

@pytest.mark.asyncio
async def test_esphome_connect_failure_apiclient_timeout(esphome_instance, mock_mdns_service):
    mock_service_info = MagicMock()  # Use MagicMock not AsyncMock for sync methods
    mock_service_info.parsed_scoped_addresses.return_value = ["10.0.0.1"]
    mock_service_info.port = 6053
    mock_mdns_service.get_info = AsyncMock(return_value=mock_service_info)

    mock_apiclient_instance = AsyncMock()
    mock_apiclient_instance.connect = AsyncMock(side_effect=TimeoutAPIError("Connection timeout"))

    with patch('radiale.esphome.aioesphomeapi.APIClient', return_value=mock_apiclient_instance):
        with pytest.raises(APIConnectionError): # It re-raises TimeoutAPIError as APIConnectionError
            await esphome_instance.connect()

@pytest.mark.asyncio
async def test_esphome_on_disconnect_successful_reconnect(esphome_instance):
    esphome_instance.connected_state = AsyncMock()
    esphome_instance.connect = AsyncMock() # Succeeds on first call
    esphome_instance.update_services = AsyncMock()
    esphome_instance.subscribe = AsyncMock()
    esphome_instance.retries = 0 # Ensure starting retries is 0

    with patch('asyncio.sleep', AsyncMock()) as mock_sleep, \
         patch('radiale.esphome.eprint') as mock_eprint: # Mock eprint from logging module

        await esphome_instance.on_disconnect()

        esphome_instance.connected_state.assert_any_call(False) # Initial disconnect
        mock_sleep.assert_not_awaited()  # success on first try -> no backoff sleep
        mock_eprint.assert_any_call('ESP try 0 reconnect MyESPHome')
        esphome_instance.connect.assert_awaited_once()
        esphome_instance.update_services.assert_awaited_once()  # services refreshed on reconnect
        esphome_instance.connected_state.assert_any_call(True) # After reconnect
        esphome_instance.subscribe.assert_awaited_once()
        assert esphome_instance.retries == 0
        assert esphome_instance.connecting is False

@pytest.mark.asyncio
async def test_esphome_on_disconnect_retries_unbounded_with_capped_backoff(esphome_instance):
    # The reconnect loop must NEVER give up (the old code abandoned after 15 tries)
    # and must cap the exponential backoff at 60s.
    fails = 16
    esphome_instance.connected_state = AsyncMock()
    esphome_instance.connect = AsyncMock(
        side_effect=[APIConnectionError("fail")] * fails + [None])
    esphome_instance.update_services = AsyncMock()
    esphome_instance.subscribe = AsyncMock()
    esphome_instance.retries = 0

    with patch('asyncio.sleep', AsyncMock()) as mock_sleep, \
         patch('radiale.esphome.eprint'):

        await esphome_instance.on_disconnect()

        # tried past the old 15-retry ceiling, then reconnected
        assert esphome_instance.connect.await_count == fails + 1
        esphome_instance.subscribe.assert_awaited_once()
        esphome_instance.connected_state.assert_any_call(True)
        assert esphome_instance.retries == 0  # reset on success

        backoffs = [c.args[0] for c in mock_sleep.await_args_list]
        assert len(backoffs) == fails              # one sleep per failure
        assert backoffs[:5] == [5, 10, 20, 40, 60] # exponential...
        assert all(b == 60 for b in backoffs[4:])  # ...capped at 60
        assert esphome_instance.connecting is False

@pytest.mark.asyncio
async def test_esphome_subscribe(esphome_instance, mock_out_q):
    esphome_instance.cli = MagicMock()  # Use MagicMock since subscribe methods are now synchronous
    esphome_instance.cli.subscribe_states = MagicMock()
    esphome_instance.cli.subscribe_home_assistant_states = MagicMock()

    await esphome_instance.subscribe()

    # subscribe_states and subscribe_home_assistant_states are now synchronous calls
    esphome_instance.cli.subscribe_states.assert_called_once_with(ANY) # ANY for the callback
    esphome_instance.cli.subscribe_home_assistant_states.assert_called_once_with(ANY) # ANY for the callback

    # Test esp_change_callback (passed to subscribe_states)
    esp_change_callback = esphome_instance.cli.subscribe_states.call_args[0][0]
    mock_state = MagicMock()
    mock_state.key = 12345 # ESPHome state keys are often uint32
    mock_state.state = "ON"
    esp_change_callback(mock_state)
    mock_out_q.write_msg.assert_any_call(
        id="esp_test_id",
        data={"service-name": "MyESPHome", "state": ["12345", "ON"]}
    )
    mock_out_q.reset_mock()

    # Test esp_subscribe_ha_state (passed to subscribe_home_assistant_states)
    esp_ha_state_callback = esphome_instance.cli.subscribe_home_assistant_states.call_args[0][0]
    entity_id = "sensor.outside_temp"
    attribute = "temperature"
    esp_ha_state_callback(entity_id, attribute) # state is not passed by this callback to our handler
    mock_out_q.write_msg.assert_any_call(
        id="esp_test_id",
        data={"service-name": "MyESPHome", "ha-state-subscribe": [entity_id, attribute]}
    )

@pytest.mark.asyncio
async def test_esphome_update_services(esphome_instance, mock_out_q):
    esphome_instance.cli = AsyncMock()

    # Mock entity and service objects
    mock_entity1 = MagicMock()
    mock_entity1.key = 111
    mock_entity1.name = "Switch One"
    mock_entity1.object_id = "switch_one"
    mock_entity1.to_dict = MagicMock(return_value={'key': 111, 'name': 'Switch One', 'object_id': 'switch_one'})

    mock_user_service1 = MagicMock()
    mock_user_service1.key = 222
    mock_user_service1.name = "My Custom Service"
    mock_user_service1.to_dict = MagicMock(return_value={'key': 222, 'name': 'My Custom Service'})


    esphome_instance.cli.list_entities_services = AsyncMock(return_value=([mock_entity1], [mock_user_service1]))

    await esphome_instance.update_services()

    esphome_instance.cli.list_entities_services.assert_awaited_once()

    expected_service_details = {
        "111": {**mock_entity1.to_dict(), "type": "service"},
        "222": {**mock_user_service1.to_dict(), "type": "user-defined-service"}
    }
    assert esphome_instance.service_details == expected_service_details
    mock_out_q.write_msg.assert_called_once_with(
        id="esp_test_id",
        data={"service-name": "MyESPHome", "services": expected_service_details}
    )

# --- Tests for command methods ---
@pytest.mark.asyncio
async def test_esphome_switch_command(esphome_instance, mock_out_q):
    esphome_instance.cli = MagicMock()  # switch_command is synchronous now
    esphome_instance.connected = True   # _is_live() requires a live, connected client
    esphome_instance.cli.switch_command = MagicMock()

    await esphome_instance.switch_command(id="cmd_sw_1", key=123, state=True)

    esphome_instance.cli.switch_command.assert_called_once_with(123, True)
    mock_out_q.write_msg.assert_called_once_with(id="cmd_sw_1", data={"success": True})

@pytest.mark.asyncio
async def test_esphome_light_command(esphome_instance, mock_out_q):
    esphome_instance.cli = MagicMock()  # light_command is synchronous now
    esphome_instance.connected = True   # _is_live() requires a live, connected client
    esphome_instance.cli.light_command = MagicMock()
    params = {"state": True, "brightness": 128}

    await esphome_instance.light_command(id="cmd_lt_1", key=456, params=params)

    # The actual code passes key as positional arg, then **params
    esphome_instance.cli.light_command.assert_called_once_with(456, **params)
    mock_out_q.write_msg.assert_called_once_with(id="cmd_lt_1", data={"success": True})

@pytest.mark.asyncio
async def test_esphome_service_command(esphome_instance, mock_out_q):
    esphome_instance.cli = MagicMock()  # execute_service is synchronous now
    esphome_instance.connected = True   # _is_live() requires a live, connected client
    esphome_instance.cli.execute_service = MagicMock()

    # Pre-populate service_details as update_services would
    mock_service_data = {'key': 789, 'name': 'test_svc', 'args': []} # Simplified
    esphome_instance.service_details = {"789": {**mock_service_data, "type": "user-defined-service"}}

    params_for_svc = {"arg1": "val1"}

    # Need to patch UserService as it's instantiated inside the method
    with patch('radiale.esphome.UserService') as MockUserServiceCls:
        mock_user_service_obj = MagicMock()
        MockUserServiceCls.return_value = mock_user_service_obj

        await esphome_instance.service_command(id="cmd_svc_1", key=789, params=params_for_svc)

        # Construct the expected dict passed to UserService constructor
        expected_svc_constructor_args = mock_service_data.copy()
        MockUserServiceCls.assert_called_once_with(**expected_svc_constructor_args)
        # execute_service is synchronous in newer aioesphomeapi
        esphome_instance.cli.execute_service.assert_called_once_with(mock_user_service_obj, params_for_svc)
        mock_out_q.write_msg.assert_called_once_with(id="cmd_svc_1", data={"success": True})

@pytest.mark.asyncio
async def test_esphome_state_update(esphome_instance, mock_out_q):
    esphome_instance.cli = MagicMock()  # send_home_assistant_state is synchronous now
    esphome_instance.connected = True   # _is_live() requires a live, connected client
    esphome_instance.cli.send_home_assistant_state = MagicMock()

    entity_id = "sensor.temp"
    attribute = "value"
    state_val = "25.5"

    await esphome_instance.state_update(id="cmd_st_1", entity_id=entity_id, attribute=attribute, state=state_val)

    esphome_instance.cli.send_home_assistant_state.assert_called_once_with(entity_id, attribute, state_val)
    mock_out_q.write_msg.assert_called_once_with(id="cmd_st_1", data={"success": True})


# --- Tests for the reconnect-wedge fix (null cli on disconnect, hard connect
#     timeout, _is_live gating) ---

@pytest.mark.asyncio
async def test_esphome_close_cli_disconnects_and_nulls(esphome_instance):
    old_cli = AsyncMock()
    esphome_instance.cli = old_cli
    esphome_instance.connected = True

    await esphome_instance._close_cli()

    old_cli.disconnect.assert_awaited_once_with(force=True)
    assert esphome_instance.cli is None
    assert esphome_instance.connected is False


def test_esphome_is_live_gating(esphome_instance):
    # nothing connected
    assert esphome_instance._is_live() is False
    # connected flag set but no client
    esphome_instance.connected = True
    esphome_instance.cli = None
    assert esphome_instance._is_live() is False
    # client present but half-open (no _connection) -> the stale-client case
    esphome_instance.cli = MagicMock()
    esphome_instance.cli._connection = None
    assert esphome_instance._is_live() is False
    # fully live
    esphome_instance.cli._connection = MagicMock()
    assert esphome_instance._is_live() is True
    # live connection but our flag cleared (the in-connect() window)
    esphome_instance.connected = False
    assert esphome_instance._is_live() is False


@pytest.mark.asyncio
async def test_esphome_connect_timeout_drops_client(esphome_instance, mock_mdns_service):
    mock_service_info = MagicMock()
    mock_service_info.parsed_scoped_addresses.return_value = ["10.0.0.1"]
    mock_service_info.port = 6053
    mock_mdns_service.get_info = AsyncMock(return_value=mock_service_info)

    mock_apiclient_instance = AsyncMock()
    mock_apiclient_instance.connect = AsyncMock(side_effect=asyncio.TimeoutError)

    with patch('radiale.esphome.aioesphomeapi.APIClient', return_value=mock_apiclient_instance):
        with pytest.raises(APIConnectionError):
            await esphome_instance.connect()

    # a connect that times out must tear the half-open client down, not leave it
    # behind for service_command to fire into.
    mock_apiclient_instance.disconnect.assert_awaited_with(force=True)
    assert esphome_instance.cli is None
    assert esphome_instance.connected is False


@pytest.mark.asyncio
async def test_esphome_service_command_not_connected(esphome_instance, mock_out_q):
    # cli object present but not live (e.g. mid-reconnect) -> must NOT fire and must
    # report failure rather than the old false success:true.
    esphome_instance.cli = MagicMock()
    esphome_instance.connected = False
    esphome_instance.service_details = {
        "789": {"key": 789, "name": "svc", "type": "user-defined-service"}}

    await esphome_instance.service_command(id="c1", key=789, params={})

    esphome_instance.cli.execute_service.assert_not_called()
    mock_out_q.write_msg.assert_called_once_with(
        id="c1", data={"success": False, "error": "Not connected"})


@pytest.mark.asyncio
async def test_esphome_service_command_awaits_async_execute_service(esphome_instance, mock_out_q):
    # aioesphomeapi 45.x makes execute_service a coroutine; it MUST be awaited or
    # the command is silently dropped (the success:true-but-nothing-sent bug).
    esphome_instance.cli = MagicMock()
    esphome_instance.connected = True
    esphome_instance.cli.execute_service = AsyncMock()  # async, as in 45.x
    esphome_instance.service_details = {
        "789": {"key": 789, "name": "svc", "type": "user-defined-service"}}

    with patch('radiale.esphome.UserService') as MockUserServiceCls:
        svc_obj = MagicMock()
        MockUserServiceCls.return_value = svc_obj
        await esphome_instance.service_command(id="c1", key=789, params={"a": 1})

    esphome_instance.cli.execute_service.assert_awaited_once_with(svc_obj, {"a": 1})
    mock_out_q.write_msg.assert_called_once_with(id="c1", data={"success": True})
