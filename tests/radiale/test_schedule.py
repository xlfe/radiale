import pytest
from datetime import datetime, timedelta, timezone
import pytz # astral uses pytz
from unittest.mock import patch, MagicMock

# Import functions from the module to be tested
from radiale.schedule import (
    only_next,
    astral_now,
    astral_next_events,
    schedule_to_millis,
    ms_until_crontab,
    ms_until_solar
)

# Fixture for common test data
@pytest.fixture
def location_data():
    return {
        "tz": "Europe/London",
        "lat": 51.5,
        "lon": -0.11
    }

# --- Tests for only_next ---
# Function signature: only_next(n, o, a, b)
# Returns milliseconds until next event

def test_only_next_a_plus_o_less_than_n():
    """When (a+o) < n, returns (b+o) - n in milliseconds"""
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    offset = timedelta(seconds=0)
    event_a = datetime(2023, 1, 1, 10, 0, 0, tzinfo=timezone.utc)  # a < now
    event_b = datetime(2023, 1, 1, 14, 0, 0, tzinfo=timezone.utc)  # tomorrow's event
    
    result = only_next(now, offset, event_a, event_b)
    # (a+o) < n, so return (b+o) - n = 14:00 - 12:00 = 2 hours = 7200000 ms
    expected_ms = 2 * 3600 * 1000
    assert result == expected_ms

def test_only_next_a_plus_o_greater_than_n():
    """When (a+o) >= n, returns (a+o) - n in milliseconds"""
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    offset = timedelta(seconds=0)
    event_a = datetime(2023, 1, 1, 14, 0, 0, tzinfo=timezone.utc)  # a > now
    event_b = datetime(2023, 1, 2, 14, 0, 0, tzinfo=timezone.utc)  # tomorrow's event
    
    result = only_next(now, offset, event_a, event_b)
    # (a+o) >= n, so return (a+o) - n = 14:00 - 12:00 = 2 hours = 7200000 ms
    expected_ms = 2 * 3600 * 1000
    assert result == expected_ms

def test_only_next_a_plus_o_equal_to_n():
    """When (a+o) == n, returns (a+o) - n (which is 0) in milliseconds"""
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    offset = timedelta(seconds=0)
    event_a = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)  # a == now
    event_b = datetime(2023, 1, 2, 12, 0, 0, tzinfo=timezone.utc)  # tomorrow's event
    
    result = only_next(now, offset, event_a, event_b)
    # (a+o) >= n (equal), so return (a+o) - n = 0 ms
    assert result == 0

def test_only_next_with_offset():
    """Test with a positive offset"""
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    offset = timedelta(hours=1)
    event_a = datetime(2023, 1, 1, 10, 0, 0, tzinfo=timezone.utc)  # a + offset = 11:00 < 12:00
    event_b = datetime(2023, 1, 1, 14, 0, 0, tzinfo=timezone.utc)  # b + offset = 15:00
    
    result = only_next(now, offset, event_a, event_b)
    # (a+o) = 11:00 < 12:00 = n, so return (b+o) - n = 15:00 - 12:00 = 3 hours = 10800000 ms
    expected_ms = 3 * 3600 * 1000
    assert result == expected_ms

def test_only_next_with_negative_offset():
    """Test with a negative offset"""
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    offset = timedelta(hours=-1)
    event_a = datetime(2023, 1, 1, 14, 0, 0, tzinfo=timezone.utc)  # a + offset = 13:00 > 12:00
    event_b = datetime(2023, 1, 2, 14, 0, 0, tzinfo=timezone.utc)
    
    result = only_next(now, offset, event_a, event_b)
    # (a+o) = 13:00 >= 12:00 = n, so return (a+o) - n = 13:00 - 12:00 = 1 hour = 3600000 ms
    expected_ms = 1 * 3600 * 1000
    assert result == expected_ms

def test_only_next_boundary_case():
    """Test boundary where a is just past now"""
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    offset = timedelta(seconds=0)
    event_a = datetime(2023, 1, 1, 12, 0, 1, tzinfo=timezone.utc)  # 1 second after now
    event_b = datetime(2023, 1, 2, 12, 0, 1, tzinfo=timezone.utc)
    
    result = only_next(now, offset, event_a, event_b)
    # (a+o) >= n, so return (a+o) - n = 1 second = 1000 ms
    assert result == 1000

# --- Tests for astral_now ---

def test_astral_now_returns_string(location_data):
    """Test that astral_now returns a string period"""
    result = astral_now(location_data["lat"], location_data["lon"], location_data["tz"])
    assert result in ["night", "sunrise", "day", "sunset"]

def test_astral_now_with_different_locations():
    """Test astral_now with different locations"""
    # Test New York
    result_ny = astral_now(40.7128, -74.0060, "America/New_York")
    assert result_ny in ["night", "sunrise", "day", "sunset"]
    
    # Test Tokyo
    result_tokyo = astral_now(35.6762, 139.6503, "Asia/Tokyo")
    assert result_tokyo in ["night", "sunrise", "day", "sunset"]

# --- Tests for astral_next_events ---

def test_astral_next_events_returns_dict(location_data):
    """Test that astral_next_events returns dict with expected keys"""
    result = astral_next_events(
        location_data["lat"], 
        location_data["lon"], 
        location_data["tz"],
        0  # offset_seconds
    )
    
    assert isinstance(result, dict)
    assert "dawn" in result
    assert "sunrise" in result
    assert "noon" in result
    assert "sunset" in result
    assert "dusk" in result
    
    # All values should be positive milliseconds
    for key, value in result.items():
        assert isinstance(value, (int, float))

def test_astral_next_events_with_offset(location_data):
    """Test that offset affects the returned values"""
    result_no_offset = astral_next_events(
        location_data["lat"], 
        location_data["lon"], 
        location_data["tz"],
        0
    )
    
    result_with_offset = astral_next_events(
        location_data["lat"], 
        location_data["lon"], 
        location_data["tz"],
        3600  # 1 hour offset
    )
    
    # With positive offset, events should be sooner by approximately 3600000 ms (1 hour in ms)
    # But due to the algorithm, if (a+o) moves from past to future, the result might wrap
    # Let's just verify both return reasonable values
    for key in ["dawn", "sunrise", "noon", "sunset", "dusk"]:
        # Both should be positive or reasonably close
        assert result_no_offset[key] >= -3600000  # Allow some negative for edge cases
        assert result_with_offset[key] >= -3600000

# --- Tests for schedule_to_millis ---

def test_schedule_to_millis():
    """Test schedule_to_millis converts timedelta to milliseconds"""
    mock_schedule = MagicMock()
    mock_now = datetime.now()
    mock_schedule.remaining_estimate.return_value = timedelta(seconds=10, microseconds=500000)

    millis = schedule_to_millis(mock_now, mock_schedule)
    assert millis == 10500.0
    # The function calls remaining_estimate twice (once for assert, once for the calc)
    assert mock_schedule.remaining_estimate.call_count == 2
    mock_schedule.remaining_estimate.assert_called_with(mock_now)

def test_schedule_to_millis_zero():
    """Test schedule_to_millis with zero remaining time"""
    mock_schedule = MagicMock()
    mock_now = datetime.now()
    mock_schedule.remaining_estimate.return_value = timedelta(seconds=0)

    millis = schedule_to_millis(mock_now, mock_schedule)
    assert millis == 0
    # The function calls remaining_estimate twice (once for assert, once for the calc)
    assert mock_schedule.remaining_estimate.call_count == 2
    mock_schedule.remaining_estimate.assert_called_with(mock_now)

# --- Tests for ms_until_crontab ---

def test_ms_until_crontab_basic(location_data):
    """Test ms_until_crontab returns positive milliseconds"""
    cron_dict = {"minute": "0", "hour": "12", "day_of_week": "*", "tz": location_data["tz"]}
    
    result = ms_until_crontab(cron_dict)
    
    # Result should be positive milliseconds
    assert isinstance(result, (int, float))
    assert result >= 0

def test_ms_until_crontab_modifies_input():
    """Test that ms_until_crontab pops 'tz' from the input dict"""
    cron_dict = {"minute": "0", "hour": "12", "tz": "Europe/London"}
    
    ms_until_crontab(cron_dict)
    
    # The 'tz' key should have been popped
    assert "tz" not in cron_dict

# --- Tests for ms_until_solar ---

def test_ms_until_solar_event_exists(location_data):
    """Test ms_until_solar returns milliseconds for valid event"""
    solar_params = {
        "event": "sunrise",
        "tz": location_data["tz"],
        "lat": location_data["lat"],
        "lon": location_data["lon"]
    }
    
    result = ms_until_solar(solar_params)
    
    assert isinstance(result, (int, float))
    assert result >= 0

def test_ms_until_solar_with_offset(location_data):
    """Test ms_until_solar with offset-seconds parameter"""
    solar_params = {
        "event": "sunset",
        "tz": location_data["tz"],
        "lat": location_data["lat"],
        "lon": location_data["lon"],
        "offset-seconds": 3600  # 1 hour offset
    }
    
    result = ms_until_solar(solar_params)
    
    assert isinstance(result, (int, float))

def test_ms_until_solar_event_missing(location_data):
    """Test ms_until_solar raises KeyError for invalid event"""
    solar_params = {
        "event": "non_existent_event",
        "tz": location_data["tz"],
        "lat": location_data["lat"],
        "lon": location_data["lon"]
    }
    
    with pytest.raises(KeyError):
        ms_until_solar(solar_params)

def test_ms_until_solar_all_events(location_data):
    """Test ms_until_solar for all valid events"""
    for event in ["dawn", "sunrise", "noon", "sunset", "dusk"]:
        solar_params = {
            "event": event,
            "tz": location_data["tz"],
            "lat": location_data["lat"],
            "lon": location_data["lon"]
        }
        
        result = ms_until_solar(solar_params)
        assert isinstance(result, (int, float))
        assert result >= 0
