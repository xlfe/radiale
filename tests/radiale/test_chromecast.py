import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

from radiale.chromecast import Chromecast, SERVICE_TYPE
from radiale.pod import OutgoingQ # For type hinting/spec
from radiale.mdns import MDNS    # For type hinting/spec

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
def chromecast_instance(mock_out_q, mock_mdns_service):
    return Chromecast(out=mock_out_q, id="cc_test_id", mdns=mock_mdns_service, sn="My Chromecast")

# --- Tests for Chromecast class ---

def test_chromecast_init(chromecast_instance, mock_out_q, mock_mdns_service):
    assert chromecast_instance.out == mock_out_q
    assert chromecast_instance.id == "cc_test_id"
    assert chromecast_instance.mdns == mock_mdns_service
    assert chromecast_instance.service_name == "My Chromecast"
    assert not hasattr(chromecast_instance, 'cc') # dmcast.Chromecast instance not yet created

@pytest.mark.asyncio
async def test_chromecast_connect_success(chromecast_instance, mock_mdns_service, mock_out_q):
    mock_service_info = MagicMock()  # Use MagicMock not AsyncMock for sync methods
    mock_service_info.parsed_scoped_addresses.return_value = ["192.168.1.100"]
    mock_service_info.port = 8009
    mock_mdns_service.get_info = AsyncMock(return_value=mock_service_info)

    mock_dmcast_cc_instance = AsyncMock()
    mock_dmcast_cc_instance.start = AsyncMock()

    # Create a future for the create_task return value
    async def awaitable_task():
        return None

    # Patch dmcast module directly at the import location in radiale.chromecast
    with patch.object(__import__('radiale.chromecast', fromlist=['dmcast']), 'dmcast') as mock_dmcast_module, \
         patch('asyncio.create_task', return_value=awaitable_task()) as mock_create_task:
        
        mock_dmcast_module.Chromecast = MagicMock(return_value=mock_dmcast_cc_instance)

        await chromecast_instance.connect()

        mock_mdns_service.get_info.assert_awaited_once_with(SERVICE_TYPE, "My Chromecast")
        mock_dmcast_module.Chromecast.assert_called_once_with("192.168.1.100", 8009)
        assert chromecast_instance.cc == mock_dmcast_cc_instance

        # Check that notify_state is set on the dmcast instance
        assert callable(chromecast_instance.cc.notify_state)

        mock_create_task.assert_called_once()

        # Test the inner notify_state callback that was set
        test_state_payload = {"app": "Netflix", "volume": 0.5}

        # Simulate dmcast calling the notify_state callback
        inner_notify_state_callback = mock_dmcast_cc_instance.notify_state
        await inner_notify_state_callback(test_state_payload)

        mock_out_q.write_msg.assert_called_once_with(
            id="cc_test_id",
            data={
                "service-name": "My Chromecast",
                "state": test_state_payload
            }
        )


@pytest.mark.asyncio
async def test_chromecast_connect_failure_no_hosts(chromecast_instance, mock_mdns_service):
    mock_service_info = MagicMock()  # Use MagicMock not AsyncMock for sync methods
    mock_service_info.parsed_scoped_addresses.return_value = [] # No hosts found
    mock_mdns_service.get_info = AsyncMock(return_value=mock_service_info)

    # Patch dmcast module to avoid import errors
    with patch.object(__import__('radiale.chromecast', fromlist=['dmcast']), 'dmcast') as mock_dmcast_module:
        mock_dmcast_module.Chromecast = MagicMock()
        
        with pytest.raises(Exception) as excinfo:
            await chromecast_instance.connect()

        assert f"Not found: {chromecast_instance.service_name}" in str(excinfo.value)
        mock_mdns_service.get_info.assert_awaited_once_with(SERVICE_TYPE, chromecast_instance.service_name)

@pytest.mark.asyncio
async def test_chromecast_connect_mdns_info_none(chromecast_instance, mock_mdns_service):
    mock_mdns_service.get_info.return_value = None # MDNS returns None

    with pytest.raises(AssertionError): # Current code will raise AssertionError if info is None
        await chromecast_instance.connect()

    mock_mdns_service.get_info.assert_awaited_once_with(SERVICE_TYPE, chromecast_instance.service_name)


@pytest.mark.asyncio
async def test_chromecast_command(chromecast_instance, mock_out_q):
    # First, ensure 'cc' (the dmcast.Chromecast instance) is set on the instance
    # It's normally set during 'connect()'. For this unit test, we mock it directly.
    chromecast_instance.cc = AsyncMock()

    # Mock a specific command method on the 'cc' object
    mock_specific_command_fn = AsyncMock()
    setattr(chromecast_instance.cc, "play_media", mock_specific_command_fn) # Example command

    command_name = "play_media"
    command_params = {"url": "http://example.com/video.mp4", "content_type": "video/mp4"}
    command_id = "cmd_play_1"

    await chromecast_instance.command(id=command_id, name=command_name, params=command_params)

    mock_specific_command_fn.assert_awaited_once_with(**command_params)
    mock_out_q.write_msg.assert_called_once_with(id=command_id, data={"success": True})

@pytest.mark.asyncio
async def test_chromecast_command_non_existent(chromecast_instance, mock_out_q):
    """Test that calling a command that exists on AsyncMock works"""
    chromecast_instance.cc = AsyncMock()
    
    command_name = "some_command"
    command_params = {"arg1": "val1"}
    command_id = "cmd_1"

    # AsyncMock by default allows any attribute access, returning another AsyncMock
    await chromecast_instance.command(id=command_id, name=command_name, params=command_params)

    # The command should have been called
    chromecast_instance.cc.some_command.assert_awaited_once_with(**command_params)
    mock_out_q.write_msg.assert_called_once_with(id=command_id, data={"success": True})
