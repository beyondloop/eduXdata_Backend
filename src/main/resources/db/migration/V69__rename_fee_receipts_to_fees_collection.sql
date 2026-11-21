-- Keep existing fee receipt data while adopting the requested collection table name.
ALTER TABLE fee_receipts RENAME TO fees_collection;

ALTER INDEX IF EXISTS idx_fee_receipts_tenant_year RENAME TO idx_fees_collection_tenant_year;
ALTER INDEX IF EXISTS idx_fee_receipts_student RENAME TO idx_fees_collection_student;
