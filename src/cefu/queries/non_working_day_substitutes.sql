with nwd as (
  select
    nwd.exception_date,
    ifsapp.work_time_calendar_api.get_closest_work_day(
      '*',
      nwd.exception_date
    ) as cd,
    ifsapp.work_time_calendar_api.get_prior_work_day(
      '*',
      nwd.exception_date
    ) as nd
  from ifsapp.work_time_exception_code nwd
  where nwd.exception_code = 'NWD'
    and nwd.day_type = 'NWD'
)
select
  exception_date as exception,
  case
    when trunc(nwd.cd, 'mm') = trunc(exception_date, 'mm') then
      cd
    else
      nd
  end as substitute
from nwd
