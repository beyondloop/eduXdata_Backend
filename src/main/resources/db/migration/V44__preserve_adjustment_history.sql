ALTER TABLE teacher_adjustments ALTER COLUMN workload_id DROP NOT NULL;
ALTER TABLE teacher_adjustments DROP CONSTRAINT teacher_adjustments_workload_id_fkey;
ALTER TABLE teacher_adjustments ADD CONSTRAINT teacher_adjustments_workload_id_fkey FOREIGN KEY(workload_id) REFERENCES teacher_workloads(id) ON DELETE SET NULL;
