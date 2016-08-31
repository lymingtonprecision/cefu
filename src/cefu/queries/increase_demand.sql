declare
  project_id_ ifsapp.project_misc_procurement.project_id%type;
  sub_project_id_ ifsapp.activity.sub_project_id%type;
  contract_ ifsapp.project_misc_procurement.site%type;
  part_no_ ifsapp.project_misc_procurement.part_no%type;
  required_date_ date;
  increase_ number;

  current_qty_ number;

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
  increase_ := :qty;

  select
    max(pmp.require_qty) keep (dense_rank last order by pmp.objid),
    max(pmp.objid),
    max(pmp.objversion) keep (dense_rank last order by pmp.objid)
  into
    current_qty_,
    objid_,
    objver_
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
  ;

  ifsapp.project_misc_procurement_api.modify__(info_, objid_, objver_, attr_, 'PREPARE');
  ifsapp.client_sys.add_to_attr('REQUIRE_QTY', current_qty_ + increase_, attr_);
  ifsapp.project_misc_procurement_api.modify__(info_, objid_, objver_, attr_, 'DO');
end;
