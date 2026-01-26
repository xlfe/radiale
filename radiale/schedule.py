from datetime import datetime, timedelta
import pytz

from astral.sun import sun
from astral import LocationInfo


# l = LocationInfo('name', 'region', 'timezone/name', 0.1, 1.2)

def only_next(n, o, a, b):

    if (a+o) < n:
        c = (b+o)
    else:
        c = (a+o)

    return (c - n).total_seconds() * 1000


def astral_now(lat, lon, tz):

    city = LocationInfo(None, None, tz, lat, lon)
    tz = pytz.timezone(tz)
    now = datetime.now(tz=tz)
    s = sun(city.observer, date=now, tzinfo=tz)

    if now < s['dawn']:
        return "night"

    elif now < s['sunrise']:
        return "sunrise"

    elif now < s['sunset']:
        return "day"

    elif now < s['dusk']:
        return "sunset"

    else:
        return "night"


def astral_next_events(lat, lon, tz, offset_seconds):

    city = LocationInfo(None, None, tz, lat, lon)
    tz = pytz.timezone(tz)
    now = datetime.now(tz=tz)
    offset = timedelta(seconds=offset_seconds)

    today = sun(city.observer, date=now, tzinfo=tz)
    tomorrow = sun(city.observer, date=now + timedelta(days=1), tzinfo=tz)

    return {
            'dawn':     only_next(now, offset, today['dawn'], tomorrow['dawn']),
            'sunrise':  only_next(now, offset, today['sunrise'], tomorrow['sunrise']),
            'noon':     only_next(now, offset, today['noon'], tomorrow['noon']),
            'sunset':   only_next(now, offset, today['sunset'], tomorrow['sunset']),
            'dusk':     only_next(now, offset, today['dusk'], tomorrow['dusk'])
            }


def ms_until_crontab(c):
    """Calculate milliseconds until the next crontab match.
    
    Note: Celery's crontab.remaining_estimate is designed for Celery's beat 
    scheduler and doesn't work correctly with timezone-aware datetimes outside
    of Celery. We calculate the next run time manually instead.
    """
    tz_str = c.pop("tz")
    tz = pytz.timezone(tz_str)
    now = datetime.now(tz=tz)
    
    hour = c.get("hour", "*")
    minute = c.get("minute", "*")
    day_of_week = c.get("day_of_week", "*")
    
    # Parse hour and minute (assuming single values for now, not ranges)
    target_hour = int(hour) if hour != "*" else now.hour
    target_minute = int(minute) if minute != "*" else now.minute
    
    # Parse day_of_week - can be "*", "0-4", "5,6", etc.
    # Python weekday: 0=Monday, 6=Sunday
    if day_of_week == "*":
        valid_days = set(range(7))
    else:
        valid_days = set()
        for part in str(day_of_week).split(","):
            if "-" in part:
                start, end = part.split("-")
                valid_days.update(range(int(start), int(end) + 1))
            else:
                valid_days.add(int(part))
    
    # Find the next valid datetime
    candidate = now.replace(hour=target_hour, minute=target_minute, second=0, microsecond=0)
    
    # Check up to 8 days ahead (covers all weekday combinations)
    for days_ahead in range(8):
        check_date = candidate + timedelta(days=days_ahead)
        if check_date.weekday() in valid_days and check_date > now:
            delta = check_date - now
            return delta.total_seconds() * 1000
    
    # Fallback - shouldn't happen with valid day_of_week
    raise ValueError(f"Could not find next run time for crontab: {c}")


def ms_until_solar(s):
    d = astral_next_events(s['lat'], s['lon'], s['tz'],
                           s['offset-seconds'] if 'offset-seconds' in s else 0)
    return d[s['event']]
