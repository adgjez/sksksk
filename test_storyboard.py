#!/usr/bin/env python3
"""
端到端测试 StoryboardBuilder 的 LLM 调用 + JSON 解析。
模拟 App 中实际调用路径，暴露解析失败原因。

用法:
    export AGNES_API_KEY="你的key"
    python3 test_storyboard.py

或:
    python3 test_storyboard.py --api-key "你的key" --base-url "https://apihub.agnes-ai.com/v1"
"""

import json, os, sys, re, argparse, time, textwrap

# ============================================================
# 模拟 App 中的 StoryboardBuilder 逻辑
# ============================================================

def strip_code_fence(s: str) -> str:
    """去掉可能的 ```json ... ``` 包裹，对应 StoryboardBuilder.stripCodeFence"""
    s = s.strip()
    if s.startswith("```"):
        first_newline = s.find("\n")
        if first_newline > 0:
            body = s[first_newline + 1:]
            end = body.rfind("```")
            if end >= 0:
                body = body[:end]
            return body
    return s


def parse_storyboard(resp: str) -> dict | None:
    """模拟 StoryboardBuilder.parse + Storyboard.fromJson"""
    cleaned = strip_code_fence(resp).strip()
    if not cleaned:
        return None
    try:
        obj = json.loads(cleaned)
    except json.JSONDecodeError as e:
        print(f"    [PARSE FAIL] JSON 解析失败: {e}")
        print(f"    [PARSE FAIL] 清理后内容(前500字): {cleaned[:500]}")
        return None

    # 验证结构: {"title": "...", "shots": [{"index":..., "imagePrompt":..., "motionHint":..., "durationMs":..., "subtitle":...}]}
    if not isinstance(obj, dict):
        print(f"    [PARSE FAIL] 顶层不是对象: {type(obj)}")
        return None
    if "shots" not in obj or not isinstance(obj["shots"], list):
        print(f"    [PARSE FAIL] 缺少 shots 数组。顶层 keys: {list(obj.keys())}")
        return None
    if len(obj["shots"]) == 0:
        print(f"    [PARSE FAIL] shots 数组为空")
        return None

    for i, shot in enumerate(obj["shots"]):
        for field in ["index", "imagePrompt", "motionHint", "durationMs", "subtitle"]:
            if field not in shot:
                print(f"    [WARN] 镜头 {i} 缺少字段 '{field}'")
    return obj


# ============================================================
# HTTP 调用 (模拟 AgnesProvider.chatLLM)
# ============================================================

def chat_llm(base_url: str, api_key: str, messages: list, json_schema: str | None,
             model: str = "agnes-2.0-flash") -> str:
    """
    模拟 AgnesProvider.chatLLM 的完整调用。
    对应 App 中: 先塞 JSON 约束 system 消息, 再塞用户消息, 加 response_format。
    """
    url = base_url.rstrip("/") + "/chat/completions"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    arr = []
    # 若要求 JSON 输出，先放 system 约束消息 (对应 App 行 56-61)
    if json_schema:
        arr.append({
            "role": "system",
            "content": f"严格按 JSON 格式输出，不要 markdown 代码块。Schema: {json_schema}"
        })
    for m in messages:
        arr.append(m)

    body = {
        "model": model,
        "messages": arr,
    }
    if json_schema:
        body["response_format"] = {"type": "json_object"}

    import urllib.request
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code}: {e.read().decode('utf-8', errors='replace')[:500]}")
        raise
    except Exception as e:
        print(f"  网络错误: {e}")
        raise

    # 模拟 parseChatContent
    choices = raw.get("choices", [])
    if not choices:
        raise Exception(f"LLM 响应无 choices: {json.dumps(raw, ensure_ascii=False)[:500]}")
    msg = choices[0].get("message", {})
    content = msg.get("content", "")
    if not content:
        raise Exception(f"LLM 响应无 content: {json.dumps(raw, ensure_ascii=False)[:500]}")
    return content


# ============================================================
# 测试用例
# ============================================================

TEST_CONTENT = """暮色如潮水般漫过长安城，朱雀大街两侧的灯笼次第亮起。

李玄策推开酒肆的木门，寒风裹着雪粒扑在脸上。他紧了紧身上的玄色披风，
目光扫过街角——那里站着一个戴斗笠的人影，一动不动。

"阁下等了很久吧。"李玄策的声音平静如水。

斗笠人缓缓转身，露出一张苍白瘦削的脸。"三年了，"他的声音沙哑，
"我每天都在等。"

李玄策的手按上了腰间的剑柄。街上的行人似乎察觉到了什么，纷纷避让。
一只野猫从墙头跳过，惊落了几片瓦，在寂静中发出清脆的碎裂声。

"当年的事，我很抱歉。"李玄策说。

"抱歉？"斗笠人冷笑，"你的一句抱歉，能换回我妹妹的命吗？"

话音未落，刀光已至。"""

TEST_STYLE = "水墨武侠"

SCHEMA = '{"title":"string","shots":[{"index":int,"imagePrompt":"string","motionHint":"string","durationMs":int,"subtitle":"string"}]}'


def build_system_prompt(target_duration_sec: int, style_hint: str | None) -> str:
    """模拟 StoryboardBuilder.build 中的 systemPrompt 构建"""
    parts = []
    parts.append(f"你是短视频分镜师。根据给定小说文本，产出 {target_duration_sec} 秒左右的分镜表。")
    parts.append("镜头数 = 目标时长 / 单镜头时长(默认5秒)，向上取整。")
    if style_hint:
        parts.append(f"统一视觉风格：{style_hint}。")
    parts.append("imagePrompt 用英文写画面描述，含场景/主体/光线/景别；motionHint 用中文写运镜；subtitle 用中文，≤8字。")
    parts.append("严格输出 JSON，无 markdown 代码块。")
    return "".join(parts)


def run_test(base_url: str, api_key: str, target_duration_sec: int = 30, style_hint: str | None = None):
    """运行一次完整测试"""
    print("=" * 70)
    print(f"端到端测试: StoryboardBuilder LLM 调用 + JSON 解析")
    print(f"Base URL: {base_url}")
    print(f"Model: agnes-2.0-flash")
    print(f"目标时长: {target_duration_sec}s")
    print(f"风格: {style_hint or '(无)'}")
    print(f"文本长度: {len(TEST_CONTENT)} 字符")
    print("=" * 70)

    system_prompt = build_system_prompt(target_duration_sec, style_hint)
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": f"文本：\n{TEST_CONTENT}"},
    ]

    print("\n[1] 发送 LLM 请求...")
    print(f"    system prompt 长度: {len(system_prompt)} 字符")
    print(f"    messages 数量: {len(messages)} (注意: chatLLM 会额外前置一条 JSON 约束 system 消息)")

    t0 = time.time()
    try:
        raw_content = chat_llm(base_url, api_key, messages, SCHEMA)
    except Exception as e:
        print(f"\n[FAIL] LLM 调用失败: {e}")
        return False
    elapsed = time.time() - t0

    print(f"    耗时: {elapsed:.1f}s")
    print(f"\n[2] LLM 原始输出 ({len(raw_content)} 字符):")
    print("─" * 60)
    print(raw_content)
    print("─" * 60)

    print(f"\n[3] stripCodeFence 后:")
    cleaned = strip_code_fence(raw_content)
    print("─" * 60)
    print(cleaned)
    print("─" * 60)

    print(f"\n[4] JSON 解析 + 结构验证:")
    result = parse_storyboard(raw_content)
    if result is None:
        print("\n[FAIL] 解析失败！")
        print("\n尝试不传入 response_format 的方式重试...")
        return False

    print(f"    ✓ title: {result.get('title', '(无)')}")
    print(f"    ✓ shots 数量: {len(result['shots'])}")
    for i, shot in enumerate(result["shots"]):
        print(f"    ── 镜头 {i} ──")
        print(f"       imagePrompt: {shot.get('imagePrompt', '')[:80]}...")
        print(f"       motionHint:  {shot.get('motionHint', '')}")
        print(f"       durationMs:  {shot.get('durationMs', '?')}")
        print(f"       subtitle:    {shot.get('subtitle', '')}")

    print(f"\n[PASS] 分镜表生成 + 解析成功!")
    return True


# ============================================================
# 改进版测试：不传 response_format (一些模型不支持)
# ============================================================

def chat_llm_no_response_format(base_url: str, api_key: str, messages: list,
                                 json_schema: str | None, model: str = "agnes-2.0-flash") -> str:
    """不传 response_format 的版本，只靠 system prompt 约束 JSON 格式"""
    url = base_url.rstrip("/") + "/chat/completions"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    arr = []
    if json_schema:
        arr.append({
            "role": "system",
            "content": f"严格按 JSON 格式输出，不要 markdown 代码块。Schema: {json_schema}"
        })
    for m in messages:
        arr.append(m)

    body = {
        "model": model,
        "messages": arr,
    }
    # 不传 response_format

    import urllib.request
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"    HTTP {e.code}: {e.read().decode('utf-8', errors='replace')[:500]}")
        raise

    choices = raw.get("choices", [])
    if not choices:
        raise Exception(f"无 choices: {json.dumps(raw, ensure_ascii=False)[:500]}")
    content = choices[0].get("message", {}).get("content", "")
    if not content:
        raise Exception(f"无 content: {json.dumps(raw, ensure_ascii=False)[:500]}")
    return content


def run_test_no_format(base_url: str, api_key: str, target_duration_sec: int = 30,
                       style_hint: str | None = None):
    """不传 response_format 的测试"""
    print("\n" + "=" * 70)
    print("备选方案: 不传 response_format (纯 prompt 约束 JSON 格式)")
    print("=" * 70)

    system_prompt = build_system_prompt(target_duration_sec, style_hint)
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": f"文本：\n{TEST_CONTENT}"},
    ]

    t0 = time.time()
    try:
        raw_content = chat_llm_no_response_format(base_url, api_key, messages, SCHEMA)
    except Exception as e:
        print(f"[FAIL] {e}")
        return False
    elapsed = time.time() - t0

    print(f"耗时: {elapsed:.1f}s")
    print(f"\n原始输出 ({len(raw_content)} 字符):")
    print("─" * 60)
    print(raw_content)
    print("─" * 60)

    result = parse_storyboard(raw_content)
    if result:
        print(f"\n[PASS] 不传 response_format 也能解析成功! shots={len(result['shots'])}")
        return True
    else:
        print(f"\n[FAIL] 不传 response_format 解析失败")
        return False


# ============================================================
# 宽松解析备选方案
# ============================================================

def parse_storyboard_lenient(resp: str) -> dict | None:
    """宽松解析：尝试多种方式提取 JSON"""
    s = resp.strip()

    # 策略1: 直接解析
    strategies = [
        ("直接解析", s),
        ("stripCodeFence", strip_code_fence(s)),
    ]

    # 策略2: 找第一个 { 到最后一个 }
    first = s.find("{")
    last = s.rfind("}")
    if first >= 0 and last > first:
        strategies.append((f"提取 JSON 块 ({first}:{last})", s[first:last+1]))

    for name, candidate in strategies:
        try:
            obj = json.loads(candidate)
            if isinstance(obj, dict) and "shots" in obj and isinstance(obj["shots"], list) and len(obj["shots"]) > 0:
                print(f"    ✓ 用策略 '{name}' 解析成功")
                return obj
        except json.JSONDecodeError:
            continue

    return None


def run_test_lenient(base_url: str, api_key: str):
    """用宽松解析重试"""
    print("\n" + "=" * 70)
    print("宽松解析器测试")
    print("=" * 70)

    system_prompt = build_system_prompt(30, "水墨武侠")
    # 更强的 prompt
    system_prompt += "\n你的回复必须只包含一个 JSON 对象，首字符是 { 末字符是 }，禁止任何其他文字。"
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": f"文本：\n{TEST_CONTENT}"},
    ]

    t0 = time.time()
    try:
        raw_content = chat_llm_no_response_format(base_url, api_key, messages, SCHEMA)
    except Exception as e:
        print(f"[FAIL] {e}")
        return False
    elapsed = time.time() - t0

    print(f"耗时: {elapsed:.1f}s")
    print(f"\n原始输出 ({len(raw_content)} 字符):")
    print("─" * 60)
    print(raw_content)
    print("─" * 60)

    result = parse_storyboard_lenient(raw_content)
    if result:
        print(f"\n[PASS] 宽松解析成功! title={result.get('title')}, shots={len(result['shots'])}")
        for i, shot in enumerate(result["shots"]):
            print(f"    镜头 {i}: {shot.get('subtitle', '')} - {shot.get('imagePrompt', '')[:60]}...")
        return True
    else:
        print(f"\n[FAIL] 宽松解析也失败")
        return False


# ============================================================
# Main
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="StoryboardBuilder LLM 端到端测试")
    parser.add_argument("--api-key", help="Agnes API Key (也可用环境变量 AGNES_API_KEY)")
    parser.add_argument("--base-url", default="https://apihub.agnes-ai.com/v1",
                        help="Agnes API Base URL")
    parser.add_argument("--duration", type=int, default=30, help="目标时长(秒)")
    parser.add_argument("--style", default="水墨武侠", help="视觉风格提示")
    parser.add_argument("--all", action="store_true", help="运行所有测试变体")
    args = parser.parse_args()

    api_key = args.api_key or os.environ.get("AGNES_API_KEY")
    if not api_key:
        print("错误: 请设置 AGNES_API_KEY 环境变量或使用 --api-key")
        sys.exit(1)

    print(f"API Key: {api_key[:8]}...{api_key[-4:]}")
    print()

    # 测试1: 模拟 App 中实际调用 (传 response_format)
    ok = run_test(args.base_url, api_key, args.duration, args.style)

    if not ok or args.all:
        # 测试2: 不传 response_format
        run_test_no_format(args.base_url, api_key, args.duration, args.style)

    if not ok or args.all:
        # 测试3: 宽松解析器
        run_test_lenient(args.base_url, api_key)

    print("\n" + "=" * 70)
    print("测试完成。根据上述结果决定修复方案：")
    print("  1. 若 LLM 输出带 markdown 包裹 → 增强 stripCodeFence")
    print("  2. 若 LLM 输出 JSON 结构不对 → 调整 system prompt")
    print("  3. 若 response_format 导致问题 → 移除或换用其他方式")
    print("=" * 70)


if __name__ == "__main__":
    main()