alter table ledger drop constraint ck_ledger_account_code_separator;
alter table ledger drop constraint ck_ledger_account_code_widths;

alter table ledger_account disable trigger tr_ledger_account_hierarchy_and_lock;

update ledger_account
set code = replace(replace(code, '.', ''), '-', '')
where code like '%.%' or code like '%-%';

alter table ledger_account enable trigger tr_ledger_account_hierarchy_and_lock;

update ledger set account_code_separator = '';

alter table ledger
    add constraint ck_ledger_account_code_separator check (account_code_separator = ''),
    add constraint ck_ledger_account_code_widths check (
        account_level2_width between 1 and 8
        and account_level3_width between 1 and 8
        and account_level4_width between 1 and 8
        and 4 + account_level2_width + account_level3_width + account_level4_width <= 32);

update ledger_account account
set legacy_code = not (
    account.code ~ '^[0-9]+$'
    and length(account.code) in (
        4,
        4 + ledger.account_level2_width,
        4 + ledger.account_level2_width + ledger.account_level3_width,
        4 + ledger.account_level2_width + ledger.account_level3_width + ledger.account_level4_width))
from ledger
where ledger.id = account.ledger_id;
