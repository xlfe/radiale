import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

from radiale.mqtt import mqtt_listen
from radiale.pod import OutgoingQ # For type hinting/spec if needed for mock_out

# --- Unit tests for mqtt_listen ---

@pytest.fixture
def mock_out_queue():
    out_q = AsyncMock(spec=OutgoingQ)
    out_q.write_msg = MagicMock() # write_msg is synchronous
    return out_q

@pytest.mark.asyncio
async def test_mqtt_listen_basic_flow(mock_out_queue):
    host = "test-broker.local"
    opts = {'host': host}

    # Simulate some messages
    mock_message1 = MagicMock()
    mock_message1.topic = "test/topic/1"
    mock_message1.payload = b"payload1"

    mock_message2 = MagicMock()
    mock_message2.topic = "another/topic"
    mock_message2.payload = b"payload2"

    # Create async iterator for messages
    class AsyncIterator:
        def __init__(self, items):
            self.items = items
            self.iter = iter(self.items)

        def __aiter__(self):
            return self

        async def __anext__(self):
            try:
                return next(self.iter)
            except StopIteration:
                raise StopAsyncIteration

    # Mock the unfiltered_messages context manager
    mock_messages_ctx_mgr = MagicMock()
    mock_messages_ctx_mgr.__aenter__ = AsyncMock(return_value=AsyncIterator([mock_message1, mock_message2]))
    mock_messages_ctx_mgr.__aexit__ = AsyncMock(return_value=None)

    # Mock the client
    mock_client_instance = MagicMock()
    mock_client_instance.__aenter__ = AsyncMock(return_value=mock_client_instance)
    mock_client_instance.__aexit__ = AsyncMock(return_value=None)
    mock_client_instance.unfiltered_messages.return_value = mock_messages_ctx_mgr
    mock_client_instance.subscribe = AsyncMock()

    with patch('radiale.mqtt.Client', return_value=mock_client_instance) as MockClientCls:
        await mqtt_listen(mock_out_queue, "mqtt_id_1", opts)

        MockClientCls.assert_called_once_with(host)
        mock_client_instance.__aenter__.assert_awaited_once()
        mock_client_instance.subscribe.assert_awaited_once_with("#")

        assert mock_out_queue.write_msg.call_count == 2
        mock_out_queue.write_msg.assert_any_call(
            id="mqtt_id_1",
            data={
                "topic": "test/topic/1",
                "payload": "payload1" # Decoded
            }
        )
        mock_out_queue.write_msg.assert_any_call(
            id="mqtt_id_1",
            data={
                "topic": "another/topic",
                "payload": "payload2" # Decoded
            }
        )
        mock_client_instance.__aexit__.assert_awaited_once()


@pytest.mark.asyncio
async def test_mqtt_listen_with_username_password(mock_out_queue):
    host = "secure-broker.local"
    username = "testuser"
    password = "testpassword"
    opts = {'host': host, 'username': username, 'password': password}

    # Create empty async iterator for messages
    class EmptyAsyncIterator:
        def __aiter__(self):
            return self

        async def __anext__(self):
            raise StopAsyncIteration

    # Mock the unfiltered_messages context manager
    mock_messages_ctx_mgr = MagicMock()
    mock_messages_ctx_mgr.__aenter__ = AsyncMock(return_value=EmptyAsyncIterator())
    mock_messages_ctx_mgr.__aexit__ = AsyncMock(return_value=None)

    # Mock the client
    mock_client_instance = MagicMock()
    mock_client_instance.__aenter__ = AsyncMock(return_value=mock_client_instance)
    mock_client_instance.__aexit__ = AsyncMock(return_value=None)
    mock_client_instance._client = MagicMock()
    mock_client_instance.unfiltered_messages.return_value = mock_messages_ctx_mgr
    mock_client_instance.subscribe = AsyncMock()

    with patch('radiale.mqtt.Client', return_value=mock_client_instance) as MockClientCls:
        await mqtt_listen(mock_out_queue, "mqtt_id_2", opts)

        MockClientCls.assert_called_once_with(host)
        mock_client_instance._client.username_pw_set.assert_called_once_with(username, password)

        mock_client_instance.__aenter__.assert_awaited_once()
        mock_client_instance.subscribe.assert_awaited_once_with("#")
        mock_out_queue.write_msg.assert_not_called() # No messages simulated
        mock_client_instance.__aexit__.assert_awaited_once()

@pytest.mark.asyncio
async def test_mqtt_listen_connection_error(mock_out_queue):
    host = "error-broker.local"
    opts = {'host': host}

    mock_client_instance = AsyncMock()
    # Simulate an error during __aenter__ (e.g., connection failure)
    mock_client_instance.__aenter__.side_effect = ConnectionRefusedError("Test connection error")

    with patch('radiale.mqtt.Client', return_value=mock_client_instance) as MockClientCls:
        with pytest.raises(ConnectionRefusedError, match="Test connection error"):
            await mqtt_listen(mock_out_queue, "mqtt_id_3", opts)

        MockClientCls.assert_called_once_with(host)
        mock_client_instance.__aenter__.assert_awaited_once()
        # Other methods like subscribe or write_msg should not be called
        mock_client_instance.subscribe.assert_not_called()
        mock_out_queue.write_msg.assert_not_called()
        # __aexit__ might or might not be called depending on how the library handles errors in __aenter__
        # For robustness, we might not assert on __aexit__ if __aenter__ fails.
        # If __aenter__ completes and then an error occurs, __aexit__ should be called.
