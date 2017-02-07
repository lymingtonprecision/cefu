select
  ip.contract,
  max(ipcp.part_no) as id,
  ipcp.cust_part_no as "number"
from ifsinfo.inv_part_cust_part_no ipcp
join ifsapp.inventory_part ip
  on ip.contract = 'LPE'
  and ipcp.part_no = ip.part_no
join ifsapp.inventory_part_status_par ps
  on ip.part_status = ps.part_status
where cust_part_no is not null
  and ps.demand_flag_db = 'Y'
group by
  ip.contract,
  ipcp.cust_part_no
