# 运满满抢单助手 - Android APK

这是一个安卓原生悬浮窗APP，常驻在运满满软件上方，支持：
- 📷 一键截屏OCR提取发货地/卸货地/吨位/运费
- 🎤 实时语音转文字+关键词提取
- 📲 一键切回运满满App

## 不用装任何软件，云端编译APK的方法

### 第一步：注册GitHub账号（免费）
打开 github.com → Sign up → 填邮箱密码注册

### 第二步：上传项目
1. 点右上角「+」→ New repository
2. 名字填 `ymm-dispatch-assistant`
3. 勾选 Public
4. 点 Create repository
5. 把这个文件夹里的**所有文件**拖到网页上传区域
6. 确保目录结构不变（特别是 .github/workflows/build-apk.yml）

### 第三步：等待编译
上传完成后，点页面顶部「Actions」标签 → 左边选「Build APK」→ 等待3-5分钟（第一次会慢一些，因为要下载SDK）

### 第四步：下载APK
编译成功后（绿勾✅）→ 点进去 → 页面最下方「Artifacts」→ 点 `ymm-dispatch-assistant-debug` → 下载得到 .apk 文件

### 第五步：安装到手机
把 .apk 传到手机 → 点击安装（允许"安装未知来源应用"）

## 使用方法
1. 打开"抢单助手"
2. 点「启动悬浮窗」→ 允许截屏 → 允许麦克风
3. 打开运满满
4. 悬浮窗出现在最上层
5. 正在编译中...
