import pytest
from datetime import datetime, timedelta, timezone
import pytz # astral uses pytz
from unittest.mock import patch, MagicMock

# Import functions from the module to be tested
# Assuming radiale.schedule is accessible in the PYTHONPATH
# If not, sys.path adjustments might be needed, but pytest usually handles this.
from radiale.schedule import (
    only_next,
    astral_now,
    astral_next_events,
    schedule_to_millis,
    ms_until_crontab,
    ms_until_solar
)

# Mock astral and celery globally for all tests in this file, if they are not installed
# or to ensure no external calls are made.
# However, astral and celery are in setup.py, so they should be available.

# Fixture for common test data
@pytest.fixture
def location_data():
    return {
        "city": "TestCity",
        "region": "TestRegion",
        "tz": "Europe/London",
        "lat": 51.5,
        "lon": -0.11
    }

# --- Tests for only_next ---

def test_only_next_a_plus_o_less_than_n():
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    event_time = now + timedelta(hours=1)  # n
    other_event_time = now + timedelta(hours=2) # o
    assert only_next(event_time, other_event_time, now) == event_time

def test_only_next_a_plus_o_greater_than_n():
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    event_time = now + timedelta(hours=2)  # n
    other_event_time = now + timedelta(hours=1) # o
    assert only_next(event_time, other_event_time, now) == other_event_time

def test_only_next_a_plus_o_equal_to_n():
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    event_time = now + timedelta(hours=1)  # n
    other_event_time = now + timedelta(hours=1) # o
    assert only_next(event_time, other_event_time, now) == event_time

def test_only_next_n_is_none():
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    other_event_time = now + timedelta(hours=1) # o
    assert only_next(None, other_event_time, now) == other_event_time

def test_only_next_o_is_none():
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    event_time = now + timedelta(hours=1)  # n
    assert only_next(event_time, None, now) == event_time

def test_only_next_both_none():
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    assert only_next(None, None, now) is None

# --- Tests for astral_now ---

@patch('radiale.schedule.astral.sun.sun')
@patch('radiale.schedule.astral.LocationInfo')
def test_astral_now_various_times(MockLocationInfo, MockSun, location_data):
    mock_city = MockLocationInfo.return_value
    mock_sun_instance = MockSun.return_value

    # Define fixed event times for a consistent test
    fixed_dawn = datetime(2023, 1, 1, 6, 0, 0, tzinfo=pytz.timezone(location_data["tz"]))
    fixed_sunrise = datetime(2023, 1, 1, 7, 0, 0, tzinfo=pytz.timezone(location_data["tz"]))
    fixed_sunset = datetime(2023, 1, 1, 18, 0, 0, tzinfo=pytz.timezone(location_data["tz"]))
    fixed_dusk = datetime(2023, 1, 1, 19, 0, 0, tzinfo=pytz.timezone(location_data["tz"]))

    mock_sun_instance.return_value = {
        "dawn": fixed_dawn,
        "sunrise": fixed_sunrise,
        "sunset": fixed_sunset,
        "dusk": fixed_dusk,
    }

    test_cases = [
        (datetime(2023, 1, 1, 5, 0, 0, tzinfo=pytz.timezone(location_data["tz"])), "night"),      # Before dawn
        (datetime(2023, 1, 1, 6, 30, 0, tzinfo=pytz.timezone(location_data["tz"])), "sunrise"), # Between dawn and sunrise
        (datetime(2023, 1, 1, 12, 0, 0, tzinfo=pytz.timezone(location_data["tz"])), "day"),      # Between sunrise and sunset
        (datetime(2023, 1, 1, 18, 30, 0, tzinfo=pytz.timezone(location_data["tz"])), "sunset"), # Between sunset and dusk
        (datetime(2023, 1, 1, 20, 0, 0, tzinfo=pytz.timezone(location_data["tz"])), "night"),    # After dusk
    ]

    for time_now, expected_period in test_cases:
        with patch('radiale.schedule.datetime') as mock_datetime:
            mock_datetime.now.return_value = time_now
            mock_datetime.side_effect = lambda *args, **kw: datetime(*args, **kw) # Allow datetime construction

            period = astral_now(location_data["city"], location_data["region"], location_data["tz"], location_data["lat"], location_data["lon"])
            assert period == expected_period
            MockLocationInfo.assert_called_with(location_data["city"], location_data["region"], location_data["tz"], location_data["lat"], location_data["lon"])
            MockSun.assert_called_with(mock_city.observer, date=time_now.date(), tzinfo=mock_city.timezone)


# --- Tests for astral_next_events ---

@patch('radiale.schedule.astral.sun.sun')
@patch('radiale.schedule.astral.LocationInfo')
@patch('radiale.schedule.only_next') # Mock our own function to check calls
def test_astral_next_events(mock_only_next, MockLocationInfo, MockSun, location_data):
    mock_city = MockLocationInfo.return_value
    mock_sun_instance = MockSun.return_value

    # Define fixed event times for today and tomorrow
    tz = pytz.timezone(location_data["tz"])
    now = datetime(2023, 1, 1, 12, 0, 0, tzinfo=tz) # Fixed "now" for the test

    today_dawn = now.replace(hour=6)
    today_sunrise = now.replace(hour=7)
    today_sunset = now.replace(hour=18)
    today_dusk = now.replace(hour=19)

    tomorrow_date = (now + timedelta(days=1)).date()
    tomorrow_dawn = datetime.combine(tomorrow_date, datetime(2023,1,1,6,0,0).time(), tzinfo=tz)
    tomorrow_sunrise = datetime.combine(tomorrow_date, datetime(2023,1,1,7,0,0).time(), tzinfo=tz)

    # Mock return values for sun() for today and tomorrow
    def sun_side_effect(observer, date, tzinfo):
        if date == now.date():
            return {"dawn": today_dawn, "sunrise": today_sunrise, "sunset": today_sunset, "dusk": today_dusk}
        elif date == tomorrow_date:
            return {"dawn": tomorrow_dawn, "sunrise": tomorrow_sunrise, "sunset": "dummy_tmrw_sunset", "dusk": "dummy_tmrw_dusk"}
        return {}
    mock_sun_instance.side_effect = sun_side_effect

    # Make only_next pass through the first argument (n) for simplicity in checking
    mock_only_next.side_effect = lambda n, o, nw: n

    with patch('radiale.schedule.datetime') as mock_datetime:
        mock_datetime.now.return_value = now
        mock_datetime.side_effect = lambda *args, **kw: datetime(*args, **kw)

        events = astral_next_events(location_data["city"], location_data["region"], location_data["tz"], location_data["lat"], location_data["lon"])

        MockLocationInfo.assert_called_with(location_data["city"], location_data["region"], location_data["tz"], location_data["lat"], location_data["lon"])

        # Check sun() calls
        assert mock_sun_instance.call_count == 2
        mock_sun_instance.assert_any_call(mock_city.observer, date=now.date(), tzinfo=mock_city.timezone)
        mock_sun_instance.assert_any_call(mock_city.observer, date=tomorrow_date, tzinfo=mock_city.timezone)

        # Check that only_next was called for each relevant event pair
        # (dawn, sunrise, sunset, dusk) from today vs tomorrow
        assert mock_only_next.call_count == 4
        mock_only_next.assert_any_call(today_dawn, tomorrow_dawn, now)
        mock_only_next.assert_any_call(today_sunrise, tomorrow_sunrise, now)
        # ... and so on for sunset, dusk - if they were properly mocked above

        expected_events = {
            "dawn": int(today_dawn.timestamp() * 1000),
            "sunrise": int(today_sunrise.timestamp() * 1000),
            "sunset": int(today_sunset.timestamp() * 1000),
            "dusk": int(today_dusk.timestamp() * 1000),
        }
        assert events == expected_events

# --- Tests for schedule_to_millis ---

def test_schedule_to_millis():
    mock_schedule = MagicMock()
    mock_schedule.remaining_estimate.return_value = timedelta(seconds=10, microseconds=500000)

    millis = schedule_to_millis(mock_schedule)
    assert millis == 10500
    mock_schedule.remaining_estimate.assert_called_once()

def test_schedule_to_millis_zero():
    mock_schedule = MagicMock()
    mock_schedule.remaining_estimate.return_value = timedelta(seconds=0)

    millis = schedule_to_millis(mock_schedule)
    assert millis == 0

# --- Tests for ms_until_crontab ---

@patch('radiale.schedule.crontab') # Mock celery.schedules.crontab
@patch('radiale.schedule.pytz.timezone')
@patch('radiale.schedule.datetime') # Mock datetime used by nowfun
def test_ms_until_crontab(mock_datetime, mock_pytz_timezone, mock_celery_crontab, location_data):
    cron_dict = {"minute": "0", "hour": "12", "day_of_week": "*"}

    mock_crontab_instance = MagicMock()
    mock_celery_crontab.return_value = mock_crontab_instance

    # Mock remaining_estimate to return a specific timedelta
    fixed_remaining_estimate = timedelta(hours=1, minutes=30)
    mock_crontab_instance.remaining_estimate.return_value = fixed_remaining_estimate

    # Mock datetime.now used by nowfun
    fixed_now = datetime(2023, 1, 1, 10, 0, 0, tzinfo=pytz.timezone(location_data["tz"]))
    mock_datetime.now.return_value = fixed_now

    # Mock pytz.timezone call if nowfun uses it explicitly (it does)
    mock_pytz_timezone.return_value = pytz.timezone(location_data["tz"])

    millis = ms_until_crontab(cron_dict, location_data["tz"])

    mock_celery_crontab.assert_called_once_with(**cron_dict)

    # Check that nowfun was called by remaining_estimate
    # The nowfun argument to remaining_estimate is called with a tz-aware datetime
    # We need to ensure our mock_datetime.now was used within that nowfun.
    # The call to remaining_estimate itself is internal to Celery's crontab,
    # but it uses the nowfun we provide.
    # We can check that pytz.timezone was called to make 'now' tz-aware for crontab
    mock_pytz_timezone.assert_called_with(location_data["tz"])

    # The nowfun passed to remaining_estimate should have been called.
    # We're checking the call to our mocked datetime.now used within that nowfun.
    mock_datetime.now.assert_called_with(tz=pytz.timezone(location_data["tz"]))

    mock_crontab_instance.remaining_estimate.assert_called_once()

    expected_millis = int(fixed_remaining_estimate.total_seconds() * 1000)
    assert millis == expected_millis

# --- Tests for ms_until_solar ---

@patch('radiale.schedule.astral_next_events')
def test_ms_until_solar_event_exists(mock_astral_next_events, location_data):
    solar_params = {
        "event": "sunrise",
        "city": location_data["city"],
        "region": location_data["region"],
        "tz": location_data["tz"],
        "lat": location_data["lat"],
        "lon": location_data["lon"]
    }

    mock_events_data = {
        "dawn": 100000,
        "sunrise": 120000, # This is what we expect to be returned
        "sunset": 200000,
        "dusk": 220000
    }
    mock_astral_next_events.return_value = mock_events_data

    millis = ms_until_solar(solar_params)

    mock_astral_next_events.assert_called_once_with(
        solar_params["city"], solar_params["region"], solar_params["tz"], solar_params["lat"], solar_params["lon"]
    )
    assert millis == mock_events_data["sunrise"]

@patch('radiale.schedule.astral_next_events')
def test_ms_until_solar_event_missing(mock_astral_next_events, location_data):
    solar_params = {
        "event": "non_existent_event", # Event that won't be in the mock data
        "city": location_data["city"],
        "region": location_data["region"],
        "tz": location_data["tz"],
        "lat": location_data["lat"],
        "lon": location_data["lon"]
    }

    mock_events_data = {
        "dawn": 100000,
        "sunrise": 120000,
    }
    mock_astral_next_events.return_value = mock_events_data

    with pytest.raises(KeyError): # Expect a KeyError because the event is not found
        ms_until_solar(solar_params)

    mock_astral_next_events.assert_called_once_with(
        solar_params["city"], solar_params["region"], solar_params["tz"], solar_params["lat"], solar_params["lon"]
    )

# Example of how you might need to adjust sys.path if radiale is not installed
# import sys
# import os
# sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../../')))
# from radiale.schedule import ...
# This is usually handled by pytest if tests/ is at the same level as radiale/ and both have __init__.py
# or if the package is installed in editable mode (pip install -e .)
