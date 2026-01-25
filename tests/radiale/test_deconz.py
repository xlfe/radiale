import pytest
import asyncio
import json
from unittest.mock import AsyncMock, MagicMock, patch

from radiale.deconz import make_host, Deconz
from radiale.pod import OutgoingQ # For type hinting/spec if needed for mock_out
from websockets.exceptions import ConnectionClosedOK  # For exceptions

# --- Unit tests for make_host ---

def test_make_host_without_port():
    opts = {'host': 'deconz.local', 'api-key': 'testapikey'}
    expected_url = "http://deconz.local:80/api/testapikey"
    assert make_host(opts) == expected_url

def test_make_host_with_port():
    opts = {'host': '192.168.1.100', 'port': 8080, 'api-key': 'anotherkey'}
    expected_url = "http://192.168.1.100:8080/api/anotherkey"
    assert make_host(opts) == expected_url

# --- Unit tests for Deconz class ---

@pytest.fixture
def mock_out_queue():
    out_q = AsyncMock(spec=OutgoingQ)
    out_q.write_msg = MagicMock() # write_msg is synchronous
    return out_q

@pytest.fixture
def deconz_instance():
    return Deconz()

def test_deconz_init(deconz_instance):
    assert deconz_instance.uri is None
    assert deconz_instance.ws is None

@pytest.mark.asyncio
async def test_deconz_put(deconz_instance, mock_out_queue):
    opts = {'host': 'deconz.local', 'api-key': 'testkey', 'port': 8080}
    type_name = "lights"
    device_id = "device123"
    state_to_put = {"on": True, "bri": 254}
    test_id = "put_id_1"

    mock_response = AsyncMock()
    mock_response.ok = True
    mock_response.json = AsyncMock(return_value={"success": True, "status": "updated"})

    # Mock aiohttp.ClientSession and its put method with proper async context manager
    mock_session_instance = MagicMock()
    mock_session_instance.put.return_value.__aenter__ = AsyncMock(return_value=mock_response)
    mock_session_instance.put.return_value.__aexit__ = AsyncMock(return_value=None)
    mock_session_instance.__aenter__ = AsyncMock(return_value=mock_session_instance)
    mock_session_instance.__aexit__ = AsyncMock(return_value=None)

    with patch('radiale.deconz.aiohttp.ClientSession', return_value=mock_session_instance) as MockClientSession:
        await deconz_instance.put(mock_out_queue, test_id, opts, type_name, device_id, state_to_put)

        expected_url = f"{make_host(opts)}/{type_name}/{device_id}/state"
        MockClientSession.assert_called_once_with() # Ensure session is created
        mock_session_instance.put.assert_called_once_with(expected_url, json=state_to_put)

        mock_response.json.assert_awaited_once()
        mock_out_queue.write_msg.assert_called_once_with(
            id=test_id,
            data={"success": True, "data": {"success": True, "status": "updated"}}
        )

@pytest.mark.asyncio
async def test_deconz_put_request_not_ok(deconz_instance, mock_out_queue):
    opts = {'host': 'deconz.local', 'api-key': 'testkey'}
    test_id = "put_id_fail"

    mock_response = AsyncMock()
    mock_response.ok = False # Simulate a failed request
    mock_response.json = AsyncMock(return_value={"error": "something went wrong"})

    # Mock aiohttp.ClientSession and its put method with proper async context manager
    mock_session_instance = MagicMock()
    mock_session_instance.put.return_value.__aenter__ = AsyncMock(return_value=mock_response)
    mock_session_instance.put.return_value.__aexit__ = AsyncMock(return_value=None)
    mock_session_instance.__aenter__ = AsyncMock(return_value=mock_session_instance)
    mock_session_instance.__aexit__ = AsyncMock(return_value=None)

    with patch('radiale.deconz.aiohttp.ClientSession', return_value=mock_session_instance):
        await deconz_instance.put(mock_out_queue, test_id, opts, "sensors", "s1", {"config": "val"})

        mock_out_queue.write_msg.assert_called_once_with(
            id=test_id,
            data={"success": False, "data": {"error": "something went wrong"}}
        )


@pytest.mark.asyncio
async def test_deconz_listen_initial_config_and_one_message(deconz_instance, mock_out_queue):
    opts = {'host': 'deconz.local', 'api-key': 'testkey_listen'}
    listen_id = "listener_1"
    websocket_port = 8443

    # Mock initial config GET request
    mock_http_response = AsyncMock()
    initial_config_data = {"config": {"websocketport": websocket_port, "other_config": "val"}}
    mock_http_response.json = AsyncMock(return_value=initial_config_data)

    # Mock aiohttp.ClientSession with proper async context manager
    mock_session_instance = MagicMock()
    mock_session_instance.get.return_value.__aenter__ = AsyncMock(return_value=mock_http_response)
    mock_session_instance.get.return_value.__aexit__ = AsyncMock(return_value=None)
    mock_session_instance.__aenter__ = AsyncMock(return_value=mock_session_instance)
    mock_session_instance.__aexit__ = AsyncMock(return_value=None)

    # Mock WebSocket connection
    mock_ws_conn = AsyncMock()
    mock_ws_conn.open = True

    # Simulate receiving one message, then an exception to break the loop
    sample_ws_message_str = json.dumps({"e": "changed", "id": "1", "r": "sensors", "state": {"buttonevent": 2002}})
    mock_ws_conn.recv = AsyncMock(side_effect=[sample_ws_message_str, ConnectionClosedOK(None, None)])

    with patch('radiale.deconz.aiohttp.ClientSession', return_value=mock_session_instance) as MockHttpClientSession, \
         patch('radiale.deconz.websockets.connect', new_callable=AsyncMock, return_value=mock_ws_conn) as MockWsConnect:

        await deconz_instance.listen(mock_out_queue, listen_id, opts)

        # Verify initial HTTP GET for config
        expected_config_url = make_host(opts)
        MockHttpClientSession.assert_called_once_with()
        mock_session_instance.get.assert_called_once_with(expected_config_url)
        mock_out_queue.write_msg.assert_any_call(id=listen_id, data={"radialeconfig": initial_config_data})

        # Verify WebSocket connection attempt
        expected_ws_uri = f"ws://{opts['host']}:{websocket_port}"
        MockWsConnect.assert_awaited_once_with(expected_ws_uri)
        assert deconz_instance.uri == expected_ws_uri
        assert deconz_instance.ws == mock_ws_conn

        # Verify message received from WebSocket
        mock_ws_conn.recv.assert_awaited() # Should have been called at least once
        mock_out_queue.write_msg.assert_any_call(id=listen_id, data=json.loads(sample_ws_message_str))

        # Loop should exit due to ConnectionClosedOK
        assert mock_ws_conn.recv.call_count == 2 # Once for message, once for exception

@pytest.mark.asyncio
async def test_deconz_listen_reconnect_websocket(deconz_instance, mock_out_queue):
    opts = {'host': 'deconz.local', 'api-key': 'testkey_reconnect'}
    listen_id = "listener_reconnect"
    websocket_port = 8000

    mock_http_response = AsyncMock()
    initial_config_data = {"config": {"websocketport": websocket_port}}
    mock_http_response.json = AsyncMock(return_value=initial_config_data)

    # Mock aiohttp.ClientSession with proper async context manager
    mock_session_instance = MagicMock()
    mock_session_instance.get.return_value.__aenter__ = AsyncMock(return_value=mock_http_response)
    mock_session_instance.get.return_value.__aexit__ = AsyncMock(return_value=None)
    mock_session_instance.__aenter__ = AsyncMock(return_value=mock_session_instance)
    mock_session_instance.__aexit__ = AsyncMock(return_value=None)

    # First WebSocket connection: initially closed, then opens, receives one message, then "closes" (by raising)
    mock_ws_conn1 = AsyncMock()
    mock_ws_conn1.open = False # Start as closed to trigger reconnect logic path

    # Second WebSocket connection (after "reconnect")
    mock_ws_conn2 = AsyncMock()
    mock_ws_conn2.open = True
    ws_message_after_reconnect = json.dumps({"event": "reconnected_event"})
    mock_ws_conn2.recv = AsyncMock(side_effect=[ws_message_after_reconnect, ConnectionClosedOK(None, None)])

    # websockets.connect will be called twice - use AsyncMock for the coroutine
    async def mock_ws_connect(uri):
        if not hasattr(mock_ws_connect, 'call_count'):
            mock_ws_connect.call_count = 0
        mock_ws_connect.call_count += 1
        if mock_ws_connect.call_count == 1:
            return mock_ws_conn1
        return mock_ws_conn2

    with patch('radiale.deconz.aiohttp.ClientSession', return_value=mock_session_instance), \
         patch('radiale.deconz.websockets.connect', side_effect=mock_ws_connect) as MockWsConnect:

        await deconz_instance.listen(mock_out_queue, listen_id, opts)

        # Assert connect was called twice
        assert MockWsConnect.call_count == 2
        expected_ws_uri = f"ws://{opts['host']}:{websocket_port}"
        MockWsConnect.assert_any_call(expected_ws_uri) # First call
        MockWsConnect.assert_any_call(expected_ws_uri) # Second call (reconnect)

        # Check message from after reconnect
        mock_out_queue.write_msg.assert_any_call(id=listen_id, data=json.loads(ws_message_after_reconnect))

        # Ensure the first ws mock (mock_ws_conn1) did not have recv called since it started as not open
        mock_ws_conn1.recv.assert_not_called()
        # Ensure the second ws mock (mock_ws_conn2) had recv called
        assert mock_ws_conn2.recv.call_count == 2

@pytest.mark.asyncio
async def test_deconz_listen_no_websocket_uri_after_config(deconz_instance, mock_out_queue):
    opts = {'host': 'deconz.local', 'api-key': 'testkey_no_ws'}
    listen_id = "listener_no_ws"

    # Config data that does *not* result in self.uri being set (e.g. missing websocketport)
    # NOTE: The actual code will still set uri if websocketport is present but may error
    # Let's test with config that has websocketport but we want to see error handling
    # Actually, looking at the code, it will always set uri if websocketport exists
    # Let's test the happy path where config has no websocketport - this will cause KeyError
    mock_http_response = AsyncMock()
    initial_config_data = {"config": {}} # No websocketport
    mock_http_response.json = AsyncMock(return_value=initial_config_data)

    # Mock aiohttp.ClientSession with proper async context manager
    mock_session_instance = MagicMock()
    mock_session_instance.get.return_value.__aenter__ = AsyncMock(return_value=mock_http_response)
    mock_session_instance.get.return_value.__aexit__ = AsyncMock(return_value=None)
    mock_session_instance.__aenter__ = AsyncMock(return_value=mock_session_instance)
    mock_session_instance.__aexit__ = AsyncMock(return_value=None)

    with patch('radiale.deconz.aiohttp.ClientSession', return_value=mock_session_instance), \
         patch('radiale.deconz.websockets.connect') as MockWsConnect:

        # The function will raise KeyError because websocketport is missing
        with pytest.raises(KeyError):
            await deconz_instance.listen(mock_out_queue, listen_id, opts)

        mock_out_queue.write_msg.assert_any_call(id=listen_id, data={"radialeconfig": initial_config_data})
        MockWsConnect.assert_not_called() # Websocket connect should not be called
