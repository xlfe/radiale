from datetime import datetime, timedelta
from celery.schedules import crontab, solar
import pytz

BACKWARD = timedelta(seconds=1)


def schedule_to_millis(now, s):
    return s.remaining_estimate(now).total_seconds() * 1000


def nowfun(tz):
    return datetime.now(tz=pytz.timezone(tz)) - BACKWARD


def ms_until_crontab(c):
    tz = c.pop("tz")
    return schedule_to_millis(nowfun(tz), crontab(**c))


def ms_until_solar(s):
    tz = s.pop("tz")
    return schedule_to_millis(nowfun(tz), solar(**s))

