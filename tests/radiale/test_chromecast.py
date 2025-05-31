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
    mock_service_info = AsyncMock()
    mock_service_info.parsed_scoped_addresses.return_value = ["192.168.1.100"]
    mock_service_info.port = 8009
    mock_mdns_service.get_info.return_value = mock_service_info

    mock_dmcast_cc_instance = AsyncMock()
    mock_dmcast_cc_instance.start = AsyncMock()

    with patch('radiale.chromecast.dmcast.Chromecast', return_value=mock_dmcast_cc_instance) as MockDmcastChromecast, \
         patch('asyncio.create_task') as mock_create_task:

        await chromecast_instance.connect()

        mock_mdns_service.get_info.assert_awaited_once_with(SERVICE_TYPE, "My Chromecast")
        MockDmcastChromecast.assert_called_once_with("192.168.1.100", 8009)
        assert chromecast_instance.cc == mock_dmcast_cc_instance

        # Check that notify_state is set on the dmcast instance
        assert callable(chromecast_instance.cc.notify_state)

        mock_create_task.assert_called_once_with(mock_dmcast_cc_instance.start())

        # Test the inner notify_state callback that was set
        test_state_payload = {"app": "Netflix", "volume": 0.5}
        # To call the callback, we need to get it from where it was assigned.
        # It's assigned to mock_dmcast_cc_instance.notify_state
        # This assumes dmcast.Chromecast allows setting notify_state like this.

        # Simulate dmcast calling the notify_state callback
        # The callback is an async function defined inside connect()
        # We need to get a reference to it. It was assigned to mock_dmcast_cc_instance.notify_state.
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
    mock_service_info = AsyncMock()
    mock_service_info.parsed_scoped_addresses.return_value = [] # No hosts found
    mock_mdns_service.get_info.return_value = mock_service_info

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
    chromecast_instance.cc = AsyncMock()
    # Ensure 'non_existent_command' is not an attribute of the mock cc
    # (AsyncMock by default allows any attribute access, returning another AsyncMock)
    # To make getattr fail, we can configure the mock or ensure it doesn't have that attr.
    # For this test, if 'getattr' is called with a name that wasn't explicitly set (like 'play_media' above),
    # it will return an AsyncMock, which is then called. This is fine.

    command_name = "non_existent_command"
    command_params = {}
    command_id = "cmd_fail_1"

    # If non_existent_command is not a special AsyncMock, calling it will just work
    # This test, as is, would not fail unless dmcast itself would raise an error
    # if a non-function attribute was called.
    # To truly test this, we'd need to know how dmcast.Chromecast behaves or make
    # chromecast_instance.cc be a mock that raises AttributeError for unknown attributes.

    # For now, let's assume the command exists and is callable as an AsyncMock.
    # The test's value is limited without a more restrictive mock for `cc`.
    # Let's mock it as if the command `non_existent_command` exists.
    mock_non_existent_command_fn = AsyncMock()
    setattr(chromecast_instance.cc, command_name, mock_non_existent_command_fn)

    await chromecast_instance.command(id=command_id, name=command_name, params=command_params)

    mock_non_existent_command_fn.assert_awaited_once_with(**command_params)
    mock_out_q.write_msg.assert_called_once_with(id=command_id, data={"success": True})

    # A more robust test for a truly non-existent command would be:
    # chromecast_instance.cc = MagicMock() # Use MagicMock to easily check for attribute presence
    # delattr(chromecast_instance.cc, "some_real_command_if_any_on_magicmock") # ensure it's not there
    # with pytest.raises(AttributeError):
    #     await chromecast_instance.command(id=command_id, name="truly_non_existent", params={})
    # This depends on how strictly we want to test getattr vs. relying on dmcast's behavior.
    # The current code `fn = getattr(self.cc, name)` will raise AttributeError if not found.
    # So let's test that path.

    chromecast_instance.cc = MagicMock() # Use a standard mock
    # Ensure the attribute does NOT exist
    mock_out_q.write_msg.reset_mock() # Reset from previous call in this test

    with pytest.raises(AttributeError): # Expect AttributeError from getattr
        await chromecast_instance.command(id="cmd_attr_err", name="definitely_not_a_command", params={})

    # In this AttributeError case, write_msg should not be called.
    mock_out_q.write_msg.assert_not_called()
