import pytest
import asyncio
import json
from unittest.mock import AsyncMock, MagicMock, patch

from radiale.deconz import make_host, Deconz
from radiale.pod import OutgoingQ # For type hinting/spec if needed for mock_out
from websockets.exceptions import ConnectionClosed

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
async def test_deconz_listen_initial_config_and_messages(deconz_instance, mock_out_queue):
    """Test that listen fetches config and receives websocket messages using the async for pattern"""
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

    # Mock WebSocket connection using async for pattern
    sample_ws_messages = [
        json.dumps({"e": "changed", "id": "1", "r": "sensors", "state": {"buttonevent": 2002}}),
        json.dumps({"e": "changed", "id": "2", "r": "lights", "state": {"on": True}}),
    ]
    
    # Create a mock websocket that yields messages then raises StopAsyncIteration
    mock_ws = AsyncMock()
    mock_ws.__aiter__ = lambda self: self
    message_iter = iter(sample_ws_messages)
    
    async def mock_anext(self):
        try:
            return next(message_iter)
        except StopIteration:
            raise StopAsyncIteration
    
    mock_ws.__anext__ = lambda self: mock_anext(self)
    
    # Create a mock connect that acts as an async iterator yielding one connection then stopping
    class MockConnect:
        def __init__(self, uri):
            self.uri = uri
            self.called = False
            
        def __aiter__(self):
            return self
            
        async def __anext__(self):
            if self.called:
                raise StopAsyncIteration
            self.called = True
            return mock_ws
    
    with patch('radiale.deconz.aiohttp.ClientSession', return_value=mock_session_instance) as MockHttpClientSession, \
         patch('radiale.deconz.websockets.connect', MockConnect) as MockWsConnect:

        await deconz_instance.listen(mock_out_queue, listen_id, opts)

        # Verify initial HTTP GET for config
        expected_config_url = make_host(opts)
        MockHttpClientSession.assert_called_once_with()
        mock_session_instance.get.assert_called_once_with(expected_config_url)
        mock_out_queue.write_msg.assert_any_call(id=listen_id, data={"radialeconfig": initial_config_data})

        # Verify URI was set correctly
        expected_ws_uri = f"ws://{opts['host']}:{websocket_port}"
        assert deconz_instance.uri == expected_ws_uri

        # Verify messages received from WebSocket
        for msg in sample_ws_messages:
            mock_out_queue.write_msg.assert_any_call(id=listen_id, data=json.loads(msg))


@pytest.mark.asyncio
async def test_deconz_listen_reconnect_on_connection_closed(deconz_instance, mock_out_queue):
    """Test that deconz reconnects after a ConnectionClosed using the async for pattern"""
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

    ws_message_1 = json.dumps({"event": "first_event"})
    ws_message_2 = json.dumps({"event": "reconnected_event"})
    
    connection_count = 0
    
    # Create mock websockets that simulate connection closed and reconnection
    class MockConnect:
        def __init__(self, uri):
            self.uri = uri
            self.connection_num = 0
            
        def __aiter__(self):
            return self
            
        async def __anext__(self):
            nonlocal connection_count
            connection_count += 1
            if connection_count > 2:
                raise StopAsyncIteration
            return MockWs(connection_count)
    
    class MockWs:
        def __init__(self, conn_num):
            self.conn_num = conn_num
            self.msg_sent = False
            
        def __aiter__(self):
            return self
            
        async def __anext__(self):
            if self.msg_sent:
                if self.conn_num == 1:
                    # First connection: raise ConnectionClosed to trigger reconnect
                    raise ConnectionClosed(None, None)
                else:
                    # Second connection: stop iteration
                    raise StopAsyncIteration
            self.msg_sent = True
            return ws_message_1 if self.conn_num == 1 else ws_message_2

    with patch('radiale.deconz.aiohttp.ClientSession', return_value=mock_session_instance), \
         patch('radiale.deconz.websockets.connect', MockConnect):

        await deconz_instance.listen(mock_out_queue, listen_id, opts)

        # Assert connect iterator was used at least twice (initial + reconnect after error)
        # It may try a 3rd time before the StopAsyncIteration is raised
        assert connection_count >= 2

        # Check both messages were received
        mock_out_queue.write_msg.assert_any_call(id=listen_id, data=json.loads(ws_message_1))
        mock_out_queue.write_msg.assert_any_call(id=listen_id, data=json.loads(ws_message_2))


@pytest.mark.asyncio
async def test_deconz_listen_no_websocket_port_in_config(deconz_instance, mock_out_queue):
    """Test that missing websocketport in config raises KeyError"""
    opts = {'host': 'deconz.local', 'api-key': 'testkey_no_ws'}
    listen_id = "listener_no_ws"

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
