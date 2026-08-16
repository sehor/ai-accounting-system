-- Rolling projections preserve signed debit and credit facts independently.
-- Both columns can therefore be populated in one period; net direction is derived at read time.
alter table dimension_period_balance
    drop constraint ck_dimension_period_balance_original_directions,
    drop constraint ck_dimension_period_balance_base_directions;
