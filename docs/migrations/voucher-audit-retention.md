# Voucher audit retention

Voucher deletion now appends a final `DELETE` revision and preserves all rows in `audit_revision` after the
voucher is physically removed. The audit table intentionally has no foreign key to `voucher` and no cascade.

Audit revisions deleted by earlier application versions cannot be reconstructed from the remaining database
state. This migration does not create synthetic history; historical gaps must remain documented as unavailable.

Source workflows own their business records. They must clear fixed-asset depreciation, disposal, or
period-closing references before calling the generated-voucher deletion command.
