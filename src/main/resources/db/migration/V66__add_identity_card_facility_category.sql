ALTER TABLE facility_products
    DROP CONSTRAINT IF EXISTS facility_products_category_check;

ALTER TABLE facility_products
    ADD CONSTRAINT facility_products_category_check
    CHECK (category IN ('BAG', 'UNIFORM', 'ID_CARD', 'BOOK', 'OTHER'));
