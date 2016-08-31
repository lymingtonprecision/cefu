declare
  project_id_ ifsapp.project_misc_procurement.project_id%type;
  sub_project_id_ ifsapp.activity.sub_project_id%type;
  contract_ ifsapp.project_misc_procurement.site%type;
  part_no_ ifsapp.project_misc_procurement.part_no%type;
  required_date_ date;
  decrease_ number;

  info_ varchar2(32000);
  objid_ ifsapp.project_misc_procurement.objid%type;
  objver_ ifsapp.project_misc_procurement.objversion%type;
  attr_ varchar2(32000);
begin
  project_id_ := :project_id;
  sub_project_id_ := :sub_project_id;
  contract_ := ifsapp.project_site_api.get_project_default_site(project_id_);
  part_no_ := :part_id;
  required_date_ := :date;
  decrease_ := :qty;

  for r in (
    select
      pmp.require_qty,
      pmp.objid,
      pmp.objversion
    from ifsapp.project_misc_procurement pmp
    join ifsapp.activity a
      on pmp.activity_seq = a.activity_seq
    where pmp.project_id = project_id_
      and a.sub_project_id = sub_project_id_
      and pmp.site = contract_
      and pmp.part_no = part_no_
      and a.objstate not in ('Cancelled', 'Completed', 'Closed')
      and nvl(
        ifsapp.project_misc_procurement_api.calculate_required_date(
          pmp.project_id,
          pmp.activity_seq,
          a.early_finish,
          pmp.offset
        ),
        a.early_finish
      ) = required_date_
    order by
      pmp.require_qty
  ) loop
    objid_ := r.objid;
    objver_ := r.objversion;

    if (decrease_ >= r.require_qty) then
      ifsapp.project_misc_procurement_api.remove__(info_, objid_, objver_, 'DO');
    else
      ifsapp.project_misc_procurement_api.modify__(info_, objid_, objver_, attr_, 'PREPARE');
      ifsapp.client_sys.add_to_attr('REQUIRE_QTY', r.require_qty - decrease_, attr_);
      ifsapp.project_misc_procurement_api.modify__(info_, objid_, objver_, attr_, 'DO');
    end if;

    decrease_ := decrease_ - r.require_qty;

    if (decrease_ <= 0) then
      exit;
    end if;
  end loop;
end;
