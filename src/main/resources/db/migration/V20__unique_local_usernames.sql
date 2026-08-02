update app_user
set display_name = 'admini123', updated_at = now()
where id = '00000000-0000-4000-8000-000000000001'
  and issuer = 'local'
  and lower(display_name) = 'admin';

with duplicates as (
    select id,
           row_number() over (
               partition by lower(display_name)
               order by case
                   when id = 'a2757c7a-fb97-4979-8f4f-abe3e401dacc' then 0
                   when id = '00000000-0000-4000-8000-000000000001' then 0
                   else 1
               end, created_at, id
           ) as occurrence
    from app_user
    where issuer = 'local' and deleted_at is null
)
update app_user u
set display_name = left(u.display_name, 163) || '-' || u.id,
    updated_at = now()
from duplicates d
where u.id = d.id and d.occurrence > 1;

create unique index ux_app_user_local_username_ci
    on app_user (lower(display_name))
    where issuer = 'local' and deleted_at is null;
