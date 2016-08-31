select
  'LPE' as contract,
  max(part_no) as id,
  cust_part_no as "number"
from ifsinfo.inv_part_cust_part_no
where cust_part_no is not null
group by
  cust_part_no
