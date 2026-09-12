-- 批量测试用户（source=batch，密码统一 123456），可重复执行（幂等）
INSERT INTO sys_user (username, email, password_hash, nickname, status, source, created_at, deleted) VALUES
('test001','test001@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户001',1,'batch',NOW(),0),
('test002','test002@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户002',1,'batch',NOW(),0),
('test003','test003@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户003',1,'batch',NOW(),0),
('test004','test004@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户004',1,'batch',NOW(),0),
('test005','test005@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户005',1,'batch',NOW(),0),
('test006','test006@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户006',1,'batch',NOW(),0),
('test007','test007@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户007',1,'batch',NOW(),0),
('test008','test008@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户008',1,'batch',NOW(),0),
('test009','test009@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户009',1,'batch',NOW(),0),
('test010','test010@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户010',1,'batch',NOW(),0),
('test011','test011@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户011',1,'batch',NOW(),0),
('test012','test012@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户012',1,'batch',NOW(),0),
('test013','test013@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户013',1,'batch',NOW(),0),
('test014','test014@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户014',1,'batch',NOW(),0),
('test015','test015@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户015',1,'batch',NOW(),0),
('test016','test016@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户016',1,'batch',NOW(),0),
('test017','test017@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户017',1,'batch',NOW(),0),
('test018','test018@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户018',1,'batch',NOW(),0),
('test019','test019@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户019',1,'batch',NOW(),0),
('test020','test020@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户020',1,'batch',NOW(),0),
('test021','test021@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户021',1,'batch',NOW(),0),
('test022','test022@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户022',1,'batch',NOW(),0),
('test023','test023@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户023',1,'batch',NOW(),0),
('test024','test024@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户024',1,'batch',NOW(),0),
('test025','test025@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户025',1,'batch',NOW(),0),
('test026','test026@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户026',1,'batch',NOW(),0),
('test027','test027@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户027',1,'batch',NOW(),0),
('test028','test028@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户028',1,'batch',NOW(),0),
('test029','test029@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户029',1,'batch',NOW(),0),
('test030','test030@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户030',1,'batch',NOW(),0),
('test031','test031@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户031',1,'batch',NOW(),0),
('test032','test032@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户032',1,'batch',NOW(),0),
('test033','test033@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户033',1,'batch',NOW(),0),
('test034','test034@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户034',1,'batch',NOW(),0),
('test035','test035@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户035',1,'batch',NOW(),0),
('test036','test036@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户036',1,'batch',NOW(),0),
('test037','test037@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户037',1,'batch',NOW(),0),
('test038','test038@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户038',1,'batch',NOW(),0),
('test039','test039@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户039',1,'batch',NOW(),0),
('test040','test040@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户040',1,'batch',NOW(),0),
('test041','test041@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户041',1,'batch',NOW(),0),
('test042','test042@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户042',1,'batch',NOW(),0),
('test043','test043@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户043',1,'batch',NOW(),0),
('test044','test044@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户044',1,'batch',NOW(),0),
('test045','test045@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户045',1,'batch',NOW(),0),
('test046','test046@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户046',1,'batch',NOW(),0),
('test047','test047@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户047',1,'batch',NOW(),0),
('test048','test048@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户048',1,'batch',NOW(),0),
('test049','test049@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户049',1,'batch',NOW(),0),
('test050','test050@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户050',1,'batch',NOW(),0),
('test051','test051@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户051',1,'batch',NOW(),0),
('test052','test052@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户052',1,'batch',NOW(),0),
('test053','test053@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户053',1,'batch',NOW(),0),
('test054','test054@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户054',1,'batch',NOW(),0),
('test055','test055@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户055',1,'batch',NOW(),0),
('test056','test056@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户056',1,'batch',NOW(),0),
('test057','test057@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户057',1,'batch',NOW(),0),
('test058','test058@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户058',1,'batch',NOW(),0),
('test059','test059@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户059',1,'batch',NOW(),0),
('test060','test060@test.com','$2a$10$cLfCB8hx3AvtVOVcwMGX/uF2bjD5k4OyWaWWUQEC/01YJnkclbRZa','测试用户060',1,'batch',NOW(),0)
ON DUPLICATE KEY UPDATE username=VALUES(username);

-- 随机收藏：每个批量用户随机收藏 15 个文化（INSERT IGNORE 去重，value 1-5 增加相似度区分度）
INSERT IGNORE INTO biz_like (user_id, target_id, value, created_at)
SELECT u.id, 1 + FLOOR(RAND()*30), 1 + FLOOR(RAND()*5), NOW()
FROM sys_user u
CROSS JOIN (
  SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
  UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10
  UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
) nums
WHERE u.source='batch';

-- 清理测试数据：DELETE FROM sys_user WHERE source='batch';  -- 收藏随外键级联删除
