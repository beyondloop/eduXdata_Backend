ALTER TABLE holiday_calendars
    ALTER COLUMN created_by TYPE VARCHAR(60) USING created_by::text;
