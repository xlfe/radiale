from datetime import datetime, timedelta
from celery.schedules import crontab
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


def schedule_to_millis(now, s):
    re = s.remaining_estimate(now)
    assert type(re) is timedelta
    return s.remaining_estimate(now).total_seconds() * 1000


def nowfun(tz):
    return datetime.now(tz=pytz.timezone(tz))


def ms_until_crontab(c):
    tz = c.pop("tz")
    return schedule_to_millis(nowfun(tz), crontab(**c))


def ms_until_solar(s):
    d = astral_next_events(s['lat'], s['lon'], s['tz'],
                           s['offset-seconds'] if 'offset-seconds' in s else 0)
    return d[s['event']]
