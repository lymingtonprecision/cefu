declare
  info_   varchar2(32000);
  objid_  varchar2(2000);
  objver_ varchar2(2000);
begin
  for r in (
    select
      a.objid,
      a.objversion
    from ifsapp.activity a
    where a.project_id = :project_id
      and trunc(a.early_finish) <= (trunc(sysdate, 'mm') + interval '1' month - 1)
      and not exists (
        select
          *
        from ifsapp.project_misc_procurement pmp
        where a.activity_seq = pmp.activity_seq
      )
  ) loop
    objid_  := r.objid;
    objver_ := r.objversion;

    ifsapp.activity_api.remove__(info_, objid_, objver_, 'DO');
  end loop;
end;
