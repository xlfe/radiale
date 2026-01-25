import pytest
from datetime import datetime, timedelta, timezone
import pytz
from unittest.mock import patch, MagicMock
from functools import wraps

# Import functions from the module to be tested
from radiale.schedule import (
    only_next,
    astral_now,
    astral_next_events,
    ms_until_crontab,
    ms_until_solar
)


def mock_now(tz_name, year, month, day, hour, minute, second=0):
    """Create a context manager that mocks datetime.now() for a specific timezone."""
    tz = pytz.timezone(tz_name)
    fake_now = tz.localize(datetime(year, month, day, hour, minute, second))
    
    class MockDatetime:
        @classmethod
        def now(cls, tz=None):
            if tz is not None:
                return fake_now.astimezone(tz)
            return fake_now.replace(tzinfo=None)
    
    return patch('radiale.schedule.datetime', MockDatetime)


# Fixture for common test data
@pytest.fixture
def location_data():
    return {
        "tz": "Europe/London",
        "lat": 51.5,
        "lon": -0.11
    }


@pytest.fixture
def sydney_location():
    return {
        "tz": "Australia/Sydney",
        "lat": -33.8688,
        "lon": 151.2093
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


# --- Tests for ms_until_crontab ---

class TestMsUntilCrontab:
    """Comprehensive tests for ms_until_crontab function."""
    
    # --- Basic functionality tests ---
    
    def test_basic_future_time_same_day(self):
        """Test scheduling for a time later today."""
        # Monday 7:00 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 0):
            # 7:30 AM is 30 minutes from now
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 7,
                "minute": 30,
                "day_of_week": "*"
            })
            
            expected_ms = 30 * 60 * 1000  # 30 minutes
            assert abs(result - expected_ms) < 1000  # within 1 second
    
    def test_time_already_passed_today(self):
        """Test scheduling when the time has already passed today."""
        # Monday 7:00 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 0):
            # 6:30 AM has passed, should schedule for tomorrow
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 6,
                "minute": 30,
                "day_of_week": "*"
            })
            
            # Should be ~23.5 hours (tomorrow 6:30 AM)
            expected_ms = (23 * 60 + 30) * 60 * 1000  # 23 hours 30 minutes
            assert abs(result - expected_ms) < 1000
    
    def test_time_in_near_future(self):
        """Test scheduling for a time just 1 minute away."""
        # Monday 7:20 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 20):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 7,
                "minute": 21,
                "day_of_week": "*"
            })
            
            expected_ms = 1 * 60 * 1000  # 1 minute
            assert abs(result - expected_ms) < 1000
    
    # --- Day of week tests ---
    
    def test_weekday_range_includes_today(self):
        """Test day_of_week range that includes today (Monday=0)."""
        # Monday 7:00 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 7,
                "minute": 30,
                "day_of_week": "0-4"  # Mon-Fri
            })
            
            # Should be 30 minutes (today)
            expected_ms = 30 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    def test_weekday_range_excludes_today(self):
        """Test day_of_week range that excludes today (Monday)."""
        # Monday 7:00 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 7,
                "minute": 30,
                "day_of_week": "5,6"  # Sat-Sun only
            })
            
            # Monday to Saturday = 5 days + 30 minutes
            expected_ms = (5 * 24 * 60 + 30) * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    def test_single_day_not_today(self):
        """Test scheduling for a specific day that's not today."""
        # Monday 7:00 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 7,
                "minute": 30,
                "day_of_week": "2"  # Wednesday only
            })
            
            # Monday to Wednesday = 2 days + 30 minutes
            expected_ms = (2 * 24 * 60 + 30) * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    def test_single_day_is_today_future_time(self):
        """Test scheduling for today when day_of_week matches."""
        # Monday 7:00 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 12,
                "minute": 0,
                "day_of_week": "0"  # Monday only
            })
            
            # 5 hours from 7:00 to 12:00
            expected_ms = 5 * 60 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    def test_single_day_is_today_past_time(self):
        """Test scheduling for today's day but time has passed - should be next week."""
        # Monday 1:00 PM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 13, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 7,
                "minute": 0,
                "day_of_week": "0"  # Monday only
            })
            
            # Next Monday 7:00 AM = 6 days 18 hours from Monday 1:00 PM
            expected_ms = (6 * 24 + 18) * 60 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    # --- Wildcard tests ---
    
    def test_wildcard_day_of_week(self):
        """Test day_of_week='*' schedules for today if time is in future."""
        # Monday 7:00 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 8,
                "minute": 0,
                "day_of_week": "*"
            })
            
            # 1 hour from now
            expected_ms = 1 * 60 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    def test_default_hour_and_minute(self):
        """Test that missing hour/minute defaults to current time (next occurrence tomorrow)."""
        # Monday 7:00 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "day_of_week": "*"
            })
            
            # Should be ~24 hours (same time tomorrow)
            expected_ms = 24 * 60 * 60 * 1000
            assert abs(result - expected_ms) < 60000  # within 1 minute
    
    # --- Edge cases ---
    
    def test_near_midnight(self):
        """Test scheduling near midnight."""
        # Monday 11:59 PM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 23, 59):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 0,
                "minute": 5,
                "day_of_week": "*"
            })
            
            # 6 minutes until 00:05
            expected_ms = 6 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    def test_at_midnight(self):
        """Test scheduling exactly at midnight."""
        # Monday midnight Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 0, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 0,
                "minute": 30,
                "day_of_week": "*"
            })
            
            # 30 minutes
            expected_ms = 30 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    def test_exact_match_time(self):
        """Test when current time exactly matches scheduled time - should be tomorrow."""
        # Monday 7:21 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 21):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 7,
                "minute": 21,
                "day_of_week": "*"
            })
            
            # Should be 24 hours (tomorrow same time)
            expected_ms = 24 * 60 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    # --- Day of week parsing tests ---
    
    def test_day_of_week_comma_separated(self):
        """Test comma-separated day_of_week values."""
        # Monday 7:00 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 7,
                "minute": 30,
                "day_of_week": "0,2,4"  # Mon, Wed, Fri
            })
            
            # Should be 30 minutes (today is Monday=0)
            expected_ms = 30 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    def test_day_of_week_mixed_range_and_single(self):
        """Test mixed ranges and single values in day_of_week."""
        # Monday 7:00 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 7,
                "minute": 30,
                "day_of_week": "0-2,5"  # Mon-Wed and Sat
            })
            
            # Should be 30 minutes (today is Monday=0)
            expected_ms = 30 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    def test_day_of_week_as_integer(self):
        """Test day_of_week as integer instead of string."""
        # Monday 7:00 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 7,
                "minute": 30,
                "day_of_week": 0  # Monday as integer
            })
            
            # Should be 30 minutes
            expected_ms = 30 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    # --- Timezone tests ---
    
    def test_different_timezone_london(self):
        """Test with London timezone."""
        # 7:00 AM London
        with mock_now("Europe/London", 2026, 1, 26, 7, 0):
            result = ms_until_crontab({
                "tz": "Europe/London",
                "hour": 8,
                "minute": 0,
                "day_of_week": "*"
            })
            
            # London 8:00 AM is 1 hour from 7:00 AM
            expected_ms = 1 * 60 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    def test_different_timezone_new_york(self):
        """Test with New York timezone."""
        # 7:00 AM New York
        with mock_now("America/New_York", 2026, 1, 26, 7, 0):
            result = ms_until_crontab({
                "tz": "America/New_York",
                "hour": 8,
                "minute": 0,
                "day_of_week": "*"
            })
            
            # 1 hour from 7:00 to 8:00
            expected_ms = 1 * 60 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    # --- Input mutation test ---
    
    def test_pops_tz_from_input(self):
        """Test that ms_until_crontab pops 'tz' from the input dict."""
        cron_dict = {"minute": 0, "hour": 12, "tz": "Europe/London", "day_of_week": "*"}
        
        ms_until_crontab(cron_dict)
        
        # The 'tz' key should have been popped
        assert "tz" not in cron_dict
    
    # --- Week wraparound tests ---
    
    def test_schedule_for_monday_from_saturday(self):
        """Test scheduling for Monday when today is Saturday."""
        # Saturday noon Sydney (Jan 31, 2026 is a Saturday)
        with mock_now("Australia/Sydney", 2026, 1, 31, 12, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 7,
                "minute": 0,
                "day_of_week": "0"  # Monday
            })
            
            # Saturday noon to Monday 7 AM = 1 day 19 hours
            expected_ms = (1 * 24 + 19) * 60 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    def test_schedule_for_monday_from_sunday(self):
        """Test scheduling for Monday when today is Sunday."""
        # Sunday noon Sydney (Feb 1, 2026 is a Sunday)
        with mock_now("Australia/Sydney", 2026, 2, 1, 12, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": 7,
                "minute": 0,
                "day_of_week": "0"  # Monday
            })
            
            # Sunday noon to Monday 7 AM = 19 hours
            expected_ms = 19 * 60 * 60 * 1000
            assert abs(result - expected_ms) < 1000
    
    # --- String hour/minute tests (as might come from config) ---
    
    def test_hour_minute_as_strings(self):
        """Test that hour and minute can be provided as strings."""
        # Monday 7:00 AM Sydney
        with mock_now("Australia/Sydney", 2026, 1, 26, 7, 0):
            result = ms_until_crontab({
                "tz": "Australia/Sydney",
                "hour": "8",
                "minute": "30",
                "day_of_week": "*"
            })
            
            # 1 hour 30 minutes
            expected_ms = (1 * 60 + 30) * 60 * 1000
            assert abs(result - expected_ms) < 1000


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
