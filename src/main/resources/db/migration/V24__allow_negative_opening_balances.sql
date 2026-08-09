alter table opening_balance drop constraint ck_opening_balance_amounts;

alter table opening_balance add constraint ck_opening_balance_amounts check (
    (debit_original = 0 and debit_base = 0) or
    (credit_original = 0 and credit_base = 0)
);
