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

    mock_client_instance = AsyncMock()

    # Mock the async context manager for unfiltered_messages
    mock_messages_ctx_mgr = AsyncMock()

    # Simulate some messages
    mock_message1 = MagicMock()
    mock_message1.topic = "test/topic/1"
    mock_message1.payload = b"payload1"

    mock_message2 = MagicMock()
    mock_message2.topic = "another/topic"
    mock_message2.payload = b"payload2"

    # Make unfiltered_messages yield these messages
    # The async_for_magic_mock allows iterating over a list of items.
    # We need an async iterator, so we'll mock __aiter__ to return an object
    # whose __anext__ will yield messages and then raise StopAsyncIteration.
    class AsyncIterator:
        def __init__(self, items):
            self.items = items
            self.iter = iter(self.items)

        async def __anext__(self):
            try:
                return next(self.iter)
            except StopIteration:
                raise StopAsyncIteration

    mock_messages_ctx_mgr.__aenter__.return_value = AsyncIterator([mock_message1, mock_message2])
    mock_client_instance.unfiltered_messages.return_value = mock_messages_ctx_mgr

    # Mock connect and subscribe methods
    mock_client_instance.connect = AsyncMock() # For 'async with client:'
    mock_client_instance.subscribe = AsyncMock()

    with patch('radiale.mqtt.Client', return_value=mock_client_instance) as MockClientCls:
        await mqtt_listen(mock_out_queue, "mqtt_id_1", opts)

        MockClientCls.assert_called_once_with(host)
        # `async with client:` implies connect and disconnect (or equivalent __aenter__/__aexit__)
        # Depending on asyncio_mqtt's Client implementation, connect might be part of __aenter__
        # For this test, let's assume connect is implicitly handled by `async with`
        # or explicitly if the library requires `await client.connect()` inside.
        # The code shows `async with client:`, which means __aenter__ is key.
        # We'll assert it was entered.
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

    mock_client_instance = AsyncMock()
    # Mock the internal _client object for username_pw_set
    mock_client_instance._client = MagicMock()

    # Setup context manager and iterator to be empty for this test
    mock_messages_ctx_mgr = AsyncMock()
    mock_messages_ctx_mgr.__aenter__.return_value = AsyncIterator([]) # No messages
    mock_client_instance.unfiltered_messages.return_value = mock_messages_ctx_mgr


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
