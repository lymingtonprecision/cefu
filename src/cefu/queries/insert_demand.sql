declare
  project_id_ ifsapp.project_misc_procurement.project_id%type;
  sub_project_id_ ifsapp.activity.sub_project_id%type;
  contract_ ifsapp.project_misc_procurement.site%type;
  part_no_ ifsapp.project_misc_procurement.part_no%type;
  required_date_ date;
  qty_ number;

  activity_seq_ ifsapp.activity.activity_seq%type;

  function find_activity(
    project_id_ varchar2,
    sub_project_id_ varchar2,
    required_date_ date
  ) return ifsapp.activity.activity_seq%type is
    activity_seq_ ifsapp.activity.activity_seq%type;
  begin
    select
      min(a.activity_seq)
    into
      activity_seq_
    from ifsapp.activity a
    where a.project_id = project_id_
      and a.sub_project_id = sub_project_id_
      and a.objstate not in ('Cancelled', 'Completed', 'Closed')
      and trunc(required_date_) between trunc(a.early_start) and trunc(a.early_finish)
    ;

    return activity_seq_;
  end;

  function next_activity_no(
    project_id_ varchar2,
    sub_project_id_ varchar2
  ) return ifsapp.activity.activity_no%type is
    activity_no_ ifsapp.activity.activity_no%type;
  begin
    select
      max(a.activity_no)
    into
      activity_no_
    from ifsapp.activity a
    where a.project_id = project_id_
      and a.sub_project_id = sub_project_id_
    ;

    if (activity_no_ is not null) then
      activity_no_ := (
        regexp_substr(activity_no_, '^[^0-9]*')
        || lpad(to_number(regexp_substr(activity_no_, '\d+$')) + 1, 3, '0')
      );
    else
      activity_no_ := 'A001';
    end if;

    return activity_no_;
  end;

  function create_activity(
    project_id_ varchar2,
    sub_project_id_ varchar2,
    required_date_ date
  ) return ifsapp.activity.activity_seq%type is
    info_ varchar(32000);
    objid_ varchar2(2000);
    objver_ varchar2(2000);
    attr_ varchar2(32000);

    start_ date := trunc(required_date_, 'mm');
    finish_ date := start_ + interval '1' month - interval '1' day;
  begin
    ifsapp.activity_api.new__(info_, objid_, objver_, attr_, 'PREPARE');

    ifsapp.client_sys.add_to_attr('PROJECT_ID', project_id_, attr_);
    ifsapp.client_sys.add_to_attr('SUB_PROJECT_ID', sub_project_id_, attr_);
    ifsapp.client_sys.add_to_attr(
      'ACTIVITY_NO',
      next_activity_no(project_id_, sub_project_id_),
      attr_
    );

    ifsapp.client_sys.add_to_attr(
      'DESCRIPTION',
      regexp_replace(to_char(start_, 'Month yyyy'), '\s+', ' ') || ' Forecast',
      attr_
    );

    ifsapp.client_sys.add_to_attr('EARLY_START', start_, attr_);
    ifsapp.client_sys.add_to_attr('EARLY_FINISH', finish_, attr_);

    ifsapp.activity_api.new__(info_, objid_, objver_, attr_, 'DO');

    return ifsapp.client_sys.get_item_value('ACTIVITY_SEQ', attr_);
  end;

  function closest_work_day(activity_seq_ number, required_date_ date) return date is
    start_ date := ifsapp.activity_api.get_early_start(activity_seq_);
    finish_ date := ifsapp.activity_api.get_early_finish(activity_seq_);
  begin
    if (required_date_ - start_) <= 12 then
      return ifsapp.work_time_calendar_api.get_closest_work_day(
        ifsapp.project_api.get_calendar_id(
          ifsapp.activity_api.get_project_id(activity_seq_)
        ),
        required_date_
      );
    else
      return required_date_;
    end if;
  end closest_work_day;

  procedure create_demand(
    project_id_ varchar2,
    activity_seq_ number,
    contract_ varchar2,
    part_no_ varchar2,
    required_date_ varchar2,
    qty_ number
  ) is
    info_ varchar2(32000);
    objid_ varchar2(2000);
    objver_ varchar2(2000);
    attr_ varchar2(32000);
  begin
    ifsapp.project_misc_procurement_api.new__(info_, objid_, objver_, attr_, 'PREPARE');

    ifsapp.client_sys.add_to_attr('PROJECT_ID', project_id_, attr_);
    ifsapp.client_sys.add_to_attr('ACTIVITY_SEQ', activity_seq_, attr_);
    ifsapp.client_sys.add_to_attr('SITE', contract_, attr_);
    ifsapp.client_sys.add_to_attr('PART_NO', part_no_, attr_);
    ifsapp.client_sys.add_to_attr('PART_OWNERSHIP_DB', 'COMPANY OWNED', attr_);

    ifsapp.client_sys.add_to_attr(
      'OFFSET',
      ifsapp.project_misc_procurement_api.caclculate_offset(
        project_id_,
        activity_seq_,
        ifsapp.activity_api.get_early_finish(activity_seq_),
        closest_work_day(activity_seq_, required_date_)
      ),
      attr_
  );

    ifsapp.client_sys.add_to_attr('REQUIRE_QTY', qty_, attr_);

    ifsapp.project_misc_procurement_api.new__(info_, objid_, objver_, attr_, 'DO');
  end;
begin
  project_id_ := :project_id;
  sub_project_id_ := :sub_project_id;
  contract_ := ifsapp.project_site_api.get_project_default_site(project_id_);
  part_no_ := :part_id;
  required_date_ := :date;
  qty_ := :qty;

  activity_seq_ := find_activity(project_id_, sub_project_id_, required_date_);

  if (activity_seq_ is null) then
    activity_seq_ := create_activity(project_id_, sub_project_id_, required_date_);
  end if;

  create_demand(
    project_id_,
    activity_seq_,
    contract_,
    part_no_,
    required_date_,
    qty_
  );
end;
