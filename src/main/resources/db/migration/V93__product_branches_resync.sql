-- V92 gave every existing product its branches before its change trigger existed, so phones' copies didn't hear of
-- it: log a change for every product, and each phone's next sync brings its branches, hiding it where it isn't for.
SELECT log_product_change(client_id, id) FROM products;
