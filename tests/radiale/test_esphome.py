import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch, ANY

from radiale.esphome import ESPHome, SERVICE_TYPE
from radiale.pod import OutgoingQ # For type hinting/spec
from radiale.mdns import MDNS    # For type hinting/spec

from aioesphomeapi.core import APIConnectionError, TimeoutAPIError
from aioesphomeapi import APIClient, EntityInfo, UserService, LightInfo

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
    mock_service_info = AsyncMock()
    mock_service_info.parsed_scoped_addresses.return_value = ["10.0.0.1"]
    mock_service_info.port = 6053
    mock_mdns_service.get_info.return_value = mock_service_info

    mock_apiclient_instance = AsyncMock(spec=APIClient)
    mock_apiclient_instance.connect = AsyncMock()

    with patch('radiale.esphome.aioesphomeapi.APIClient', return_value=mock_apiclient_instance) as MockAPIClientCls:
        await esphome_instance.connect()

        mock_mdns_service.get_info.assert_awaited_once_with(SERVICE_TYPE, "MyESPHome")
        MockAPIClientCls.assert_called_once_with("10.0.0.1", 6053, None) # password is None
        assert esphome_instance.cli == mock_apiclient_instance
        mock_apiclient_instance.connect.assert_awaited_once_with(on_stop=esphome_instance.on_disconnect, login=True)

@pytest.mark.asyncio
async def test_esphome_connect_failure_no_hosts(esphome_instance, mock_mdns_service):
    mock_service_info = AsyncMock()
    mock_service_info.parsed_scoped_addresses.return_value = [] # No hosts
    mock_mdns_service.get_info.return_value = mock_service_info

    with pytest.raises(APIConnectionError): # As per code, it raises APIConnectionError directly
        await esphome_instance.connect()
    mock_mdns_service.get_info.assert_awaited_once_with(SERVICE_TYPE, "MyESPHome")

@pytest.mark.asyncio
async def test_esphome_connect_failure_apiclient_timeout(esphome_instance, mock_mdns_service):
    mock_service_info = AsyncMock()
    mock_service_info.parsed_scoped_addresses.return_value = ["10.0.0.1"]
    mock_service_info.port = 6053
    mock_mdns_service.get_info.return_value = mock_service_info

    mock_apiclient_instance = AsyncMock(spec=APIClient)
    mock_apiclient_instance.connect = AsyncMock(side_effect=TimeoutAPIError("Connection timeout"))

    with patch('radiale.esphome.aioesphomeapi.APIClient', return_value=mock_apiclient_instance):
        with pytest.raises(APIConnectionError): # It re-raises TimeoutAPIError as APIConnectionError
            await esphome_instance.connect()

@pytest.mark.asyncio
async def test_esphome_on_disconnect_successful_reconnect(esphome_instance):
    esphome_instance.connected_state = AsyncMock()
    esphome_instance.connect = AsyncMock() # Succeeds on first call
    esphome_instance.subscribe = AsyncMock()
    esphome_instance.retries = 0 # Ensure starting retries is 0

    with patch('asyncio.sleep', AsyncMock()) as mock_sleep, \
         patch('radiale.pod.eprint') as mock_eprint: # Mock eprint if it's noisy

        await esphome_instance.on_disconnect()

        esphome_instance.connected_state.assert_any_call(False) # Initial disconnect
        mock_sleep.assert_awaited_once_with(5)
        mock_eprint.assert_any_call('ESP try 0 reconnect MyESPHome')
        esphome_instance.connect.assert_awaited_once()
        esphome_instance.connected_state.assert_any_call(True) # After reconnect
        esphome_instance.subscribe.assert_awaited_once()
        assert esphome_instance.retries == 0
        assert esphome_instance.connecting is False

@pytest.mark.asyncio
async def test_esphome_on_disconnect_fails_all_retries(esphome_instance):
    esphome_instance.connected_state = AsyncMock()
    esphome_instance.connect = AsyncMock(side_effect=APIConnectionError("Persistent connection failure"))
    esphome_instance.subscribe = AsyncMock() # Should not be called
    esphome_instance.retries = 0

    with patch('asyncio.sleep', AsyncMock()) as mock_sleep, \
         patch('radiale.pod.eprint') as mock_eprint:

        await esphome_instance.on_disconnect()

        esphome_instance.connected_state.assert_called_with(False) # Only called for initial disconnect
        assert mock_sleep.call_count == 15 # Sleeps 15 times
        assert esphome_instance.connect.await_count == 15
        esphome_instance.subscribe.assert_not_called()
        assert esphome_instance.retries == 15
        assert esphome_instance.connecting is False
        mock_eprint.assert_any_call('ESP try 14 reconnect MyESPHome') # Last retry log

@pytest.mark.asyncio
async def test_esphome_subscribe(esphome_instance, mock_out_q):
    esphome_instance.cli = AsyncMock(spec=APIClient)
    esphome_instance.cli.subscribe_states = AsyncMock()
    esphome_instance.cli.subscribe_home_assistant_states = AsyncMock()

    await esphome_instance.subscribe()

    esphome_instance.cli.subscribe_states.assert_awaited_once_with(ANY) # ANY for the callback
    esphome_instance.cli.subscribe_home_assistant_states.assert_awaited_once_with(ANY) # ANY for the callback

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
    esphome_instance.cli = AsyncMock(spec=APIClient)

    # Mock entity and service objects
    mock_entity1 = MagicMock(spec=EntityInfo) # Using EntityInfo as a base for mocked entities
    mock_entity1.key = 111
    mock_entity1.name = "Switch One"
    mock_entity1.object_id = "switch_one"
    mock_entity1.to_dict = MagicMock(return_value={'key': 111, 'name': 'Switch One', 'object_id': 'switch_one'})

    mock_user_service1 = MagicMock(spec=UserService)
    mock_user_service1.key = 222
    mock_user_service1.name = "My Custom Service"
    # to_dict for UserService might be different, adapt as per actual structure
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
    esphome_instance.cli = AsyncMock(spec=APIClient)
    esphome_instance.cli.switch_command = AsyncMock()

    await esphome_instance.switch_command(id="cmd_sw_1", key=123, state=True)

    esphome_instance.cli.switch_command.assert_awaited_once_with(123, True)
    mock_out_q.write_msg.assert_called_once_with(id="cmd_sw_1", data={"success": True})

@pytest.mark.asyncio
async def test_esphome_light_command(esphome_instance, mock_out_q):
    esphome_instance.cli = AsyncMock(spec=APIClient)
    esphome_instance.cli.light_command = AsyncMock()
    params = {"state": True, "brightness": 128}

    await esphome_instance.light_command(id="cmd_lt_1", key=456, params=params)

    esphome_instance.cli.light_command.assert_awaited_once_with(key=456, **params)
    mock_out_q.write_msg.assert_called_once_with(id="cmd_lt_1", data={"success": True})

@pytest.mark.asyncio
async def test_esphome_service_command(esphome_instance, mock_out_q):
    esphome_instance.cli = AsyncMock(spec=APIClient)
    esphome_instance.cli.execute_service = AsyncMock()

    # Pre-populate service_details as update_services would
    mock_service_data = {'key': 789, 'name': 'test_svc', 'args': []} # Simplified
    esphome_instance.service_details = {"789": {**mock_service_data, "type": "user-defined-service"}}

    params_for_svc = {"arg1": "val1"}

    # Need to patch UserService as it's instantiated inside the method
    with patch('radiale.esphome.UserService', spec=UserService) as MockUserServiceCls:
        mock_user_service_obj = MagicMock(spec=UserService)
        MockUserServiceCls.return_value = mock_user_service_obj

        await esphome_instance.service_command(id="cmd_svc_1", key=789, params=params_for_svc)

        # Construct the expected dict passed to UserService constructor
        expected_svc_constructor_args = mock_service_data.copy()
        MockUserServiceCls.assert_called_once_with(**expected_svc_constructor_args)
        esphome_instance.cli.execute_service.assert_awaited_once_with(mock_user_service_obj, params_for_svc)
        mock_out_q.write_msg.assert_called_once_with(id="cmd_svc_1", data={"success": True})

@pytest.mark.asyncio
async def test_esphome_state_update(esphome_instance, mock_out_q):
    esphome_instance.cli = AsyncMock(spec=APIClient)
    esphome_instance.cli.send_home_assistant_state = AsyncMock()

    entity_id = "sensor.temp"
    attribute = "value"
    state_val = "25.5"

    await esphome_instance.state_update(id="cmd_st_1", entity_id=entity_id, attribute=attribute, state=state_val)

    esphome_instance.cli.send_home_assistant_state.assert_awaited_once_with(entity_id, attribute, state_val)
    mock_out_q.write_msg.assert_called_once_with(id="cmd_st_1", data={"success": True})
