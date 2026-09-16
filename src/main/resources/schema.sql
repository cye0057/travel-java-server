-- 用户中心三表。全部 IF NOT EXISTS：spring.sql.init 每次启动都执行，重复跑必须无副作用
-- 注意不用外键：过期清理任务删除消息/会话时避免父子表删除顺序互相牵制，归属校验在应用层做

-- 用户表：password_hash 存 BCrypt 结果（自带随机盐，同密码两次注册哈希值不同）
-- user_code 是对外的唯一标识（注册时分配，永不复用、不含自增id信息），内部关联一律用 id
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    user_code     VARCHAR(32)   NOT NULL COMMENT '对外唯一标识，如 U20260915XXXXXX',
    username      VARCHAR(32)   NOT NULL COMMENT '登录名',
    password_hash VARCHAR(100)  NOT NULL COMMENT 'BCrypt哈希，绝不存明文',
    nickname      VARCHAR(32)   NULL COMMENT '昵称，空则用username',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_user_code (user_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户表';

-- 会话表：一个 sessionId = 一段可回看的历史对话；title 取首条提问截断
-- idx_user_updated 服务于"我的历史列表"：按用户取、按最近活跃排序
CREATE TABLE IF NOT EXISTS chat_conversation (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL COMMENT '前端生成的会话uuid',
    user_id    BIGINT      NOT NULL COMMENT '归属用户users.id',
    title      VARCHAR(64) NOT NULL DEFAULT '' COMMENT '列表展示标题',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活跃时间，过期清理与排序都看它',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session (session_id),
    KEY idx_user_updated (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI对话会话表';

-- 消息表：完整对话内容持久层（Redis里那份只是展示缓存，可随时由此重建）
-- idx_session_id 服务"打开某段历史"，idx_created_at 服务每日过期删除
CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    user_id    BIGINT      NOT NULL,
    role       VARCHAR(16) NOT NULL COMMENT 'user/assistant',
    content    TEXT        NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session_id (session_id, id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI对话消息表（7天后由定时任务清理）';

-- ============ 运营配置：对话页热门问题 / 首页热门城市 ============
-- 原前端硬编码数组搬进数据库：运营改推荐位不用发版。
-- schema.sql 每次启动都会重放，种子数据必须幂等：INSERT IGNORE + 固定主键id（撞键静默跳过）。
-- 注意 IGNORE 也会吞掉其它插入错误，仅适合这种可控的种子场景；线上改数据走 SQL 手工维护即可。

CREATE TABLE IF NOT EXISTS hot_question (
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    question VARCHAR(128) NOT NULL COMMENT '展示并填入输入框的问题文本',
    sort     INT          NOT NULL DEFAULT 0 COMMENT '越小越靠前',
    enabled  TINYINT      NOT NULL DEFAULT 1 COMMENT '0=下架不删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_question (question)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '对话页热门问题';

INSERT IGNORE INTO hot_question (id, question, sort, enabled) VALUES
    (1, '下周去杭州玩，天气怎么样？有什么必去景点', 1, 1),
    (2, '北京三日游，预算1500元怎么规划？', 2, 1),
    (3, '西安有什么本地人常去的美食，给具体店名和地址', 3, 1),
    (4, '成都大熊猫基地的门票怎么预约？', 4, 1),
    (5, '上海适合晚上逛的地方推荐', 5, 1);

CREATE TABLE IF NOT EXISTS hot_city (
    id      BIGINT      NOT NULL AUTO_INCREMENT,
    city    VARCHAR(32) NOT NULL COMMENT '城市名',
    sort    INT         NOT NULL DEFAULT 0,
    enabled TINYINT     NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_city (city)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '首页热门城市';

INSERT IGNORE INTO hot_city (id, city, sort, enabled) VALUES
    (1, '北京', 1, 1), (2, '上海', 2, 1), (3, '广州', 3, 1), (4, '深圳', 4, 1),
    (5, '成都', 5, 1), (6, '杭州', 6, 1), (7, '西安', 7, 1), (8, '重庆', 8, 1),
    (9, '南京', 9, 1), (10, '武汉', 10, 1), (11, '长沙', 11, 1), (12, '厦门', 12, 1);
