declare
  info_ varchar2(32000);
  objid_ ifsapp.project_misc_procurement.objid%type;
  objver_ ifsapp.project_misc_procurement.objversion%type;
begin
  for r in (
    select
      pmp.objid,
      pmp.objversion
    from ifsapp.project_misc_procurement pmp
    join ifsapp.activity a
      on pmp.activity_seq = a.activity_seq
    where pmp.project_id = :project_id
      and a.sub_project_id = :sub_project_id
      and pmp.site = ifsapp.project_site_api.get_project_default_site(pmp.project_id)
      and pmp.part_no = :part_id
      and a.objstate not in ('Cancelled', 'Completed', 'Closed')
      and nvl(
        ifsapp.project_misc_procurement_api.calculate_required_date(
          pmp.project_id,
          pmp.activity_seq,
          a.early_finish,
          pmp.offset
        ),
        a.early_finish
      ) = :date
  ) loop
    objid_  := r.objid;
    objver_ := r.objversion;

    ifsapp.project_misc_procurement_api.remove__(
      info_, objid_, objver_, 'DO'
    );
  end loop;
end;
