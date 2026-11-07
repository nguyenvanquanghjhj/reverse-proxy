INSERT INTO products(id,sku,name,price_vnd,stock_quantity,status) VALUES
 (1,'NOTE-01','Sổ tay PBL4',49000,100,'ACTIVE'),
 (2,'PEN-01','Bút ghi chú',15000,3,'ACTIVE'),
 (3,'BAG-01','Túi canvas',89000,0,'ACTIVE'),
 (4,'OLD-01','Sổ phiên bản cũ',39000,10,'INACTIVE'),
 (5,'KIT-01','Bộ học tập',129000,50,'ACTIVE')
ON CONFLICT(id) DO NOTHING;
