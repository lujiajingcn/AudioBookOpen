-- Rate Limit Lua Script
-- KEYS[1] = 限流 key（如 "rate_limit:login:192.168.1.1"）
-- ARGV[1] = 窗口内最大请求次数
-- ARGV[2] = 窗口时长（秒）
-- 返回 1 表示放行，0 表示拒绝

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local current = redis.call('GET', key)

if current and tonumber(current) >= limit then
    return 0
end

current = redis.call('INCR', key)

if current == 1 then
    redis.call('EXPIRE', key, window)
end

return 1
