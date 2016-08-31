select
  a.sub_project_id,
  --
  pmp.site,
  pmp.part_no as part_id,
  ipcp.cust_part_no as part_number,
  --
  nvl(
    ifsapp.project_misc_procurement_api.calculate_required_date(
      pmp.project_id,
      pmp.activity_seq,
      a.early_finish,
      pmp.offset
    ),
    a.early_finish
  ) as "date",
  pmp.require_qty as qty
from ifsapp.project_misc_procurement pmp
--
join ifsapp.activity a
  on pmp.activity_seq = a.activity_seq
join ifsapp.sub_project sp
  on a.project_id = sp.project_id
  and a.sub_project_id = sp.sub_project_id
--
left outer join ifsinfo.inv_part_cust_part_no ipcp
  on pmp.part_no = ipcp.part_no
--
where pmp.project_id = :project_id
  and a.objstate not in ('Cancelled', 'Completed', 'Closed')
