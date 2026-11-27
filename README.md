# BMW MN-Launcher 4.8

BMW F07 2016 530d xDrive 5GT + MEKEDE MN-L (Android 14, 2560×720 울트라와이드) 전용
개인용 홈 런처입니다. Kotlin + Android SDK만 사용하며 네이티브 라이브러리(.so)가 없어
ARM64 실기기와 x86_64 에뮬레이터(LD플레이어 등) 모두에서 설치·실행됩니다.

2.0은 CarCar Launcher(카카런처)의 실사용 구조를 기준으로 화면을 다시 만들었고,
4.0은 그 **가상화면 엔진을 원본 APK에서 분석해 이식**하고 이름·시작 화면·여백·테마를 개편한 버전입니다.
4.1은 비율 변경 시 왼쪽 창이 까맣게 굳는 문제를 고치려 했지만 **양쪽 창이 모두 안 나오게 됐고**,
4.2는 **4.1의 회귀를 되돌려 4.0의 출력 경로를 복원**한 뒤 4.0에서 실제로 문제였던 두 가지만 고친 버전입니다.
부팅 동영상·사진 선택기가 없던 기기 문제도 함께 고쳤습니다.
4.5는 **화면을 리사이즈하지 않고 갈아끼우는** 방식으로 바꿔 비율 조정·설정 왕복에서 창이 까매지지 않게 한 버전입니다(아래 4.5 절).
4.6은 4.5에서 남은 **"비율을 바꾸면 화면 전체가 터치 먹통"** 문제를 고친 버전입니다(아래 4.6 절).
4.7은 비율 변경 때 **새 `SurfaceView`를 만들어** 디스플레이를 갈아끼우는 버전이고(아래 4.7 절),
4.8은 **다른 화면(후진 카메라·다른 앱·차량 순정 화면)에서 런처로 돌아오면 두 창이 모두 까매지던** 문제를 고친 버전입니다(아래 4.8 절).
상단 바·하단 바를 없애고 **화면 전체를 두 개의 분할 창**, 왼쪽에 **56dp 슬림 독**만 남겼습니다.

## 4.8 — 다른 화면에서 돌아오면 두 창이 모두 까매짐

실차 제보(2026-11-26, v4.6): *"4.6에서 후진을 넣으면 화면이 카메라로바뀌거든 근데 끝나고 다시 런쳐로돌아가는데
이때 화면이 모두 까매.계속 반복"* — 이어서 *"후진이 문제는 아닌것같고 다른 화면에서 다시 복귀하면 다 그런것같아"*.
후진 카메라만의 문제가 아니라 **런처 앞에 다른 창이 들어왔다가 빠지면 매번** 두 창이 검게 남는 증상입니다.

### 원인 — 돌아오는 길에 "표면이 바뀌었다"를 아무도 보지 않았다

다른 창이 런처를 덮으면 두 창의 `SurfaceView`는 표면을 잃고(`surfaceDestroyed`), 돌아올 때 **새 표면**을 받습니다.
그런데 창의 디스플레이는 **옛 BufferQueue에 묶인 채** 남습니다(4.5에서 확인한 이 펌웨어의 성질 — 새로 만든
디스플레이만 그립니다). 돌아오는 길에 그 사실을 처리하는 코드가 없었습니다.

- `VirtualDisplayEngine.resume()`은 `surfaceLost`가 기록된 창을 **건너뛰었습니다**(`if (!surfaceLost.contains(side)) continue`).
  정확히 디스플레이를 갈아끼워야 하는 창을 빠뜨린 것이고, 표면이 그 전에 새로 만들어져 `surfaceLost`가
  비워진 경우도 보지 않았습니다.
- `LauncherActivity.onResume()`은 **런처 자신의 화면(설정·앱 서랍)에서 돌아올 때만** `resume()`을 예약했습니다.
  v3.2의 자유창 가드(닫은 창이 다시 열리는 문제)를 가상 디스플레이 엔진에도 그대로 적용한 탓에,
  카메라·다른 앱에서 돌아올 때는 엔진에 아무 요청도 가지 않았습니다.

### 수정

- **돌아오기를 하나의 경로로** (`engine/VirtualDisplayEngine.kt` — `repairPane`)
  - 돌아올 때마다 창의 **지금 표면과 지금 디스플레이를 직접 확인**합니다. 표면이 디스플레이와 다르거나,
    표면을 잃었다는 기록이 있거나, 플랫폼이 그 디스플레이를 더 이상 알려주지 않으면 새 디스플레이를 만듭니다.
    콜백이나 플래그를 믿지 않으므로 콜백을 놓친 경우도 덮습니다.
  - 새 디스플레이로 앱의 루트 태스크를 옮긴 뒤, **그 태스크의 최상단 액티비티**를 `REORDER_TO_FRONT`로 깨웁니다
    (`wakeOnDisplay`). 태스크 이동만으로는 상태는 남지만, 다른 창 뒤에서 멈춘 액티비티는 다시 앞으로 오기 전까지
    프레임을 그리지 않습니다. 앱의 **메인 액티비티**를 앞으로 부르면 지도 경로·재생 화면이 앱 첫 화면으로
    바뀌어 버리므로, 태스크 정보에서 최상단 액티비티를 읽어 그것만 앞으로 옮깁니다(읽을 수 없을 때만 런치 인텐트 사용).
  - `onResume` 시점에는 창이 아직 배치·표시 전일 수 있어, 표면·크기가 준비될 때까지 **350ms 간격으로 최대 6회** 다시 확인합니다.
  - 표면을 잃었다는 기록이 남아 있으면 같은 `Surface` 객체가 돌아온 경우에도 디스플레이를 갈아끼웁니다
    (죽은 BufferQueue에 묶인 디스플레이는 같은 표면으로도 다시 그리지 않습니다).
- **돌아오는 길을 모두 잡음** (`LauncherActivity.kt`)
  - 가상 디스플레이 엔진일 때는 `onResume()`에서 **자유창 가드와 무관하게** 창 복구를 예약합니다.
    앱을 다시 띄울지는 엔진이 태스크를 확인해 결정하므로(창이 살아 있으면 아무것도 하지 않음),
    "닫은 앱이 다시 열리는" v3.2 증상은 되살아나지 않습니다.
  - 홈 키로 돌아올 때의 `onNewIntent()`, 덮고 있던 창이 포커스만 돌려줄 때의 `onWindowFocusChanged(true)`에서도
    같은 복구를 예약합니다.
- **디스플레이가 사라진 경우** — 플랫폼이 목록에서 지운 디스플레이를 물고 있던 창은 `displayAlive()`로 걸러
  새로 만듭니다(읽을 수 없으면 살아 있는 것으로 봅니다 — 모르는 채 앱을 재시작하지 않기 위해).

### 남는 트레이드오프

- 돌아올 때마다 두 창의 표면을 확인하고, 필요하면 디스플레이를 새로 만듭니다. 창이 그대로 살아 있으면
  태스크 확인 한 번만 하고 끝나므로 평소 비용은 없습니다.
- 두 창이 동시에 새로 만들어져야 하는 경우 4.6의 순서 제한(한쪽 → 180ms → 다른 쪽)을 그대로 따릅니다.
  두 번째 창은 그만큼 늦게 돌아옵니다.

## 4.7 — 비율 변경 시 새 `SurfaceView`로 갈아끼움

비율 변경·프리셋 적용처럼 창의 표면을 그대로 두고 크기만 바뀌는 경우, v4.7은 **옛 디스플레이가 아직 물고 있는
표면에 새 디스플레이를 묶지 않고** 새 `SurfaceView`를 만들어 그 표면으로 디스플레이를 만듭니다
(`mintSurfaceView` → `finishPendingSwap`). 우측 검은 여백과 비율 변경 직후의 간헐적 검은 화면이 이 경로에서 사라졌고,
프리셋은 앱을 옛 크기 디스플레이에 먼저 띄우지 않고 새 크기 디스플레이에 바로 띄웁니다(`preparePackages`).

## 4.6 — 비율 변경 후 화면 전체 터치 먹통

실차 제보(2026-11-26, v4.5): *"전보다는 좋아졌어 그런데 화면비율 변경이 들어가면 화면전체가 터치가 안되는 문제가있어. 물론 껏다키면 다시동작해"*

### 원인 — 터치 주입이 **런처 메인 스레드**에서 플랫폼을 기다리고 있었다

창을 터치하면 `VirtualDisplayEngine`의 `SurfaceView` 리스너가 그 자리(메인 스레드)에서 `SystemTouchInjector.inject()`를 불렀고,
그 안에서 두 개의 **블로킹 바인더 호출**이 돌았습니다.

1. `IWindowManager.injectInputAfterTransactionsApplied` — 원본 카카런처는 이걸 **자기 창(`Lw7/g`)의 스레드**에서 부릅니다.
   우리는 손가락이 도착하는 바로 그 스레드에서 불렀습니다. 이 호출은 *진행 중인 디스플레이 트랜잭션이 적용될 때까지* 기다립니다.
2. `IWindowManager.syncInputTransactions(true)` — 원본 `La8/c0.i`는 **제스처가 끝날 때(UP/CANCEL/HOVER_EXIT)** 부르는데,
   v4.5는 조건이 정반대라 **드래그의 `ACTION_MOVE`마다** 동기 호출을 했습니다.

비율을 바꾸면 두 창의 디스플레이가 **동시에** 새로 만들어지고, 앱 태스크가 옮겨지고, 옛 디스플레이가 해제됩니다.
그 순간이 정확히 위 두 호출이 기다리는 트랜잭션의 한가운데입니다. 메인 스레드가 거기서 멈추면,
**화면 전체(디스플레이 0)를 소유한 것이 런처 자신**이므로 이후의 모든 터치가 처리되지 않습니다.
앱을 껐다 켜면 새 프로세스·새 메인 스레드라 다시 동작합니다 — 증상과 100% 일치합니다.

### 수정

- **터치 주입을 메인 스레드에서 완전히 분리** (`touch/SystemTouchInjector.kt`)
  - `SurfaceView` 리스너는 `MotionEvent.obtain(event)`로 **복사만** 하고(프레임워크가 원본을 곧 재활용하므로),
    복사본을 전용 스레드 `MN-TouchInject`(`HandlerThread`, `THREAD_PRIORITY_DISPLAY`)에 넘깁니다.
  - 그 스레드가 `setDisplayId` → (DOWN 직전) `syncInputTransactions(true)` → `InputManager.injectInputEvent(…, MODE_ASYNC)`
    → (UP/CANCEL/HOVER_EXIT에서만) `syncInputTransactions(true)` 순으로 처리하고 복사본을 `recycle()` 합니다.
  - `injectInputAfterTransactionsApplied` 호출은 **제거**했습니다(`WindowService`에서도 삭제).
  - 주입 스레드가 밀리면(대기 24건 초과) **중간 MOVE만** 버립니다. DOWN/UP은 절대 버리지 않으므로 제스처는 끊기지 않고,
    큐가 무한정 자라지도 않습니다. 권한 조회(IPC)도 이제 한 번만 캐시합니다.
  - Shizuku 경로도 같은 결함이 있어 `IShizukuControl.injectTouch`를 **`oneway`** 로 바꾸고 동기화 조건을 원본과 같게 고쳤습니다
    (표준 APK에서 메인 스레드가 원격 프로세스를 기다리지 않음).
- **디스플레이 교체를 한 번에 하나씩** (`engine/VirtualDisplayEngine.kt`)
  - 비율 변경은 양쪽 창을 동시에 옮기므로 교체가 같은 프레임에 몰립니다. 이제 한쪽을 끝낸 뒤 **240ms** 뒤에 다른 쪽을 교체합니다.
  - 옛 디스플레이 해제도 한 번에 하나씩, 600ms 간격으로 처리합니다(즉시 해제 두 건이 겹치지 않게).
- **비율 메뉴는 닫힌 뒤에 적용** (`ui/RatioMenu.kt`)
  - `Dialog.dismiss()`가 창을 제거한 **뒤** 디스미스 콜백에서 비율을 적용합니다. 포커스를 쥐고 있던 다이얼로그 창 제거와
    디스플레이 교체 트랜잭션이 겹치지 않습니다.
- **태스크 이동 대상 검증** (`engine/TaskMover.kt`)
  - `displayId`를 받지 않는 옛 `getTasks(...)` 폴백은 **디스플레이가 명시적으로 일치하는 태스크만** 돌려줍니다
    (`strictlyOn`). 필드를 읽을 수 없으면 채택하지 않습니다.
  - `moveRootTasks()`는 디스플레이 **0**(런처 자신·차량 시스템 UI)을 출발지·도착지로 절대 받지 않습니다.

### 남는 트레이드오프

- 두 번째 창의 디스플레이 교체가 240ms 늦어집니다(그 사이 그 창은 이전 화면을 잠깐 보여 줌). 교체 중 두 창이 동시에
  트랜잭션을 만들지 않는 것이 목적이므로, 비율 변경은 "한쪽 → 다른 쪽" 순서로 진행됩니다.
- 같은 창을 900ms 안에 다시 교체하지 않는 쿨다운은 그대로입니다(디바이더를 빠르게 흔들어도 앱이 반복 재시작되지 않음).

## 4.5 — 창을 "리사이즈"하지 않고 "갈아끼움" (비율·메뉴 왕복에서 검정)

실차 제보(2026-11-26, v4.4): 부팅 동영상·음악 자동시작·터치·분할은 모두 정상. 그런데
① **비율을 조정하면 왼쪽 창이 까매지고**, ② **설정 같은 메뉴에 들어갔다 나오면 두 쪽 다 까매지고**,
③ 그 상태에서 앱을 껐다 켜면 다시 정상 — 그리고 다시 메뉴에 들어가면 또 까매지는 반복이었습니다.

### 1. 원본 카카런처는 표면을 재사용하지 않는다 (dexdump 재분석)
`carcar-3.3.10`의 `classes.dex`를 `dexdump -d`로 다시 풀어 확인한 사실입니다.

| 클래스 | 하는 일 |
|---|---|
| `La8/c0` | VirtualDisplayManager. 필드 `c` = `VirtualDisplay`, `q` = 현재 표면(Flutter `SurfaceProducer` 래퍼 `Lo1/b`), `r` = **보류된 옛 표면 목록** |
| `La8/c0.b()` | **크기 변경 경로**: ① 현재 표면을 `r`에 넣고 ② **새 `SurfaceProducer`를 발급**해 ③ `setDefaultBufferSize(w,h)` ④ `setSurface(새 표면)` ⑤ 그 다음에 `VirtualDisplay.resize(w,h,dpi)` ⑥ `DisplayListener` 등록. 옛 표면은 256 ms 뒤 `release()` |
| `La8/c0.a()` | 현재 표면을 `setSurface`하고 1024 ms 뒤 후속 패스를 예약 |
| `La8/c0.d()/e()` | 숨김 = `setSurface(null)` + `setDisplayState(false)`, 복귀 = `setDisplayState(true)` + `setSurface(현재 표면)` |
| `La8/b0` (`DisplayListener`) | `onDisplayAdded(내 id)` → `setDisplayState(true)`, `onDisplayChanged(내 id)` → 256 ms 뒤 재부착/재적용. `onDisplayRemoved`는 아무것도 안 함 |
| `La8/z` (`Runnable`) | 위 지연 패스들(0·1·2·3)의 실제 동작. case 0 = 콜백+보류 표면 정리, case 1 = `a()` + 필요 시 `b()`, case 2 = 보류 표면 `release`, case 3 = 재부착 |
| `La8/k` | AppView 래퍼. 크기 갱신(`e(DDDZLy4/b;)`)은 값이 1px 이내로 같고 배율도 같으면 콜백만 하고, 아니면 `La8/c0.b()`를 부릅니다 |

핵심은 **원본이 기하(크기)가 바뀔 때마다 출력 표면 자체를 새로 발급한다**는 점입니다.
그리고 AOSP `VirtualDisplayAdapter`를 보면 그 이유가 분명합니다.
- `setSurfaceLocked(surface)`는 `mSurface != surface`일 때만 반영합니다(= **같은 Surface 객체를 다시 붙이는 것은 아무 일도 하지 않음**).
- `resizeLocked(w,h,dpi)`도 표면을 다시 묶지 않고 `setDisplaySize`만 예약합니다.
- 둘 다 `SurfaceControl.Transaction`으로 한 번에 반영되며, **디스플레이가 살아 있는 채로 표면/크기를 바꾸는 경로**입니다.

이 펌웨어(MEKEDE MN-L, Android 14)에서는 그 "살아 있는 디스플레이를 다시 묶는" 경로가 먹히지 않습니다.
그래서 원본의 `onDisplayChanged` 재부착(`a()`)도 **표면 객체가 같으면 AOSP 수준에서 no-op**이고,
원본이 정상 동작하는 진짜 이유는 **새 표면 + 새 크기로 다시 묶기**입니다.
우리 `SurfaceView`는 표면을 마음대로 새로 발급할 수 없으므로, v4.5는 사용자가 확인해 준 유일한 정상 경로를 씁니다 — **디스플레이를 새로 만드는 것**입니다.

### 2. v4.5 엔진 (`VirtualDisplayEngine`)
1. pane의 사각형이나 표면이 지금 디스플레이가 만들어진 값과 다르면 **정착(settle) 대기** 후(드래그는 초당 수십 번 레이아웃되므로 마지막 사각형만 유효) 디스플레이를 **새로 만듭니다**(`host.create`). 크기·표면이 같으면 아무 것도 하지 않습니다(±2px 허용).
2. 새 디스플레이를 만든 뒤 **실행 중인 앱의 root task를 새 디스플레이로 옮깁니다**(`TaskMover` → `ActivityTaskManager.getService().moveRootTaskToDisplay(rootTaskId, displayId)`, 즉 `am display move-stack`과 같은 호출, 권한 `INTERNAL_SYSTEM_WINDOW`). 내비 경로·재생 상태가 유지됩니다.
3. 옮기지 못하면(권한/펌웨어 거부) **새 디스플레이에 앱을 다시 실행**합니다(`setLaunchDisplayId` + `REORDER_TO_FRONT`). 이 경로 실패는 그 창에만 상태 문구를 남기고 엔진 전체를 자유창 모드로 몰지 않습니다.
4. 그 다음에야 옛 디스플레이를 `release()` 합니다(600 ms 뒤). **생성 → 이동 → 해제 순서**를 지켜 앱 태스크가 먼저 사라지는 일이 없습니다.
5. 같은 창을 0.9초 안에 두 번 갈아끼우지 않습니다(레이아웃이 흔들려도 앱이 반복 재시작되지 않게).

### 3. 설정·앱 서랍은 "반투명 창"으로 (메뉴 왕복 근본 수정)
원본 카카런처의 설정 화면은 Flutter **내부 화면**이라 런처 Activity가 멈추지 않고, 그래서 표면이 파괴될 일이 없습니다.
우리는 별도 Activity였기 때문에 메뉴에 들어가면 `LauncherActivity`가 `onStop` → SurfaceView 표면 파괴 → 재부착이 안 먹혀 양쪽 다 까매졌습니다.
- `SettingsActivity`·`AppDrawerActivity` 테마를 `Theme.BmwLauncher.Floating`(`windowIsTranslucent=true`, 배경 검정)으로 바꿨습니다.
- 반투명 창은 아래 Activity를 가리지 않으므로 런처는 `onPause`만 되고 **표면이 그대로 살아 있습니다**. 화면은 이전과 똑같이 검정이고, 터치는 위 창이 받습니다.
- 이 경로에서는 `onResume`이 가상화면을 건드리지 않습니다(표면 객체·크기가 같으면 no-op). 앱이 사라졌는지 보는 확인도 **표면이 실제로 파괴된 경우에만** 합니다.

### 4. 그 밖의 변경
- **독 숨김은 레이아웃을 바꾸지 않습니다**(`GONE` → `INVISIBLE`). 예전에는 독을 숨길 때마다 창이 56dp 넓어졌고, v4.5 엔진에서는 그때마다 디스플레이를 갈아끼워야 해서 **6초 자동 숨김마다 앱이 재시작**될 수 있었습니다. 이제 독이 숨으면 왼쪽에 56dp 검은 띠가 남고 창은 그대로입니다.
- 창 메뉴에 **`화면 다시 그리기`** 를 넣었습니다. 어떤 이유로든 창이 까매졌을 때 그 창만 새 디스플레이로 다시 만듭니다(런처 재시작 불필요).
- `WindowService.dontOverrideDisplayInfo(displayId)`는 **새로 만드는 모든 디스플레이**에 그대로 적용됩니다(`SystemDisplayHost.create`, `ShizukuControlService.createDisplay`).
- 원본의 `onDisplayAdded → setDisplayState(true)`는 이식하지 않았습니다. v4.0~v4.4가 그 호출 없이 정상 출력했고, 검증되지 않은 상태 토글은 v4.1에서 창을 모두 잃은 전례가 있습니다. 대신 표시 장치 상태·실제 크기를 `MNLauncher` 태그로 남깁니다.

### 5. 남는 트레이드오프
- 비율 변경·전체화면 전환·화면 배율(dpi) 변경은 **디스플레이를 갈아끼우는 일**이라, root task 이동이 허용되면 앱이 그대로 유지되고, 거부되면 그 창의 앱이 한 번 다시 시작됩니다(음악은 서비스 재생이 계속, 내비는 경로를 다시 잡아야 함). 창이 까만 채로 남는 것보다는 낫다는 판단입니다.
- 화면이 꺼졌다 켜지면(차량 슬립) 표면이 파괴되므로 깨어날 때 두 창이 다시 만들어집니다. 이전 버전에서는 이 경우 창이 까맣게 남았습니다.
- 진단 로그(`adb logcat -s MNLauncher`): `pane left display 12 -> 15 (#2, 1264x720 @160 …)`, `TaskMover: moveRootTaskToDisplay(rootTask=…, display=…) -> true`, `pane left surface destroyed; display 12 kept`, `display 15 reports 1264x720, state 2`.


## 4.4 — `setFixedSize` 완전 제거 (비율 조정 시 양쪽 창 검정)

실차 제보(2026-11-26): v4.3은 **처음 켰을 때 왼쪽·오른쪽 두 창 모두 정상 출력**되었고,
**화면 비율을 조정하는 순간 두 창이 모두 까매졌습니다**.

원인은 v4.3의 조건부 핀에 그대로 남아 있던 `setFixedSize`입니다.
- 부팅 직후 50:50에서는 `surfaceFrame == width × height`라서 **핀을 건너뛰었고 → 두 창 정상**.
- 비율을 움직이는 순간 `surfaceFrame != width × height`가 되어 **`holder.setFixedSize(w,h)`가 호출**되고,
  `SurfaceView`가 표면을 파괴·재생성하는 사이에 표시 장치가 죽은 표면을 가리켜 **양쪽 창이 검게** 되었습니다.
  (원본 카카런처도 이 호출을 하지 않습니다.)

v4.4는 **엔진에서 `setFixedSize` 호출과 `pinned`/`pinAttempts` 로직을 완전히 삭제**했습니다.
`match_parent` SurfaceView는 레이아웃이 바뀌면 프레임워크가 버퍼 크기를 스스로 맞추고 `surfaceChanged`로 알려 주므로,
비율 변경 경로는 이제 **표면 객체가 바뀐 경우 `host.setSurface(existing, surface)` + `host.resize(existing, w, h, dpi)`** 만 합니다.
표면이 잠깐 사라졌다 돌아오면(`surfaceDestroyed → surfaceCreated`) 새 표면을 `attachSurface`로 다시 붙이고,
표시 장치는 그대로 두므로 앱이 재시작되지 않습니다.

## 4.3 — 표면 타이밍 수정(양쪽 창 검정) · 런처 자체 폴더 탐색기

실차 제보(2026-11-26): v4.2 설치 후 **독·분할선·회색 핸들은 정상이나 두 창 안이 모두 검정**,
그리고 부팅 동영상을 고르려 하면 펌웨어 기본 선택기가 `LOCAL > Ts` 폴더만 보여 **파일이 있는 폴더로 갈 수 없음**.

### 1. 검은 화면 — `setFixedSize`가 방금 파괴 예약한 표면을 화면에 넘기고 있었음
v4.2는 `sync()` 안에서 `holder.setFixedSize(w,h)`를 호출한 **바로 그 패스에서** `host.create(...)`/`setSurface`/
`resize`에 **그 순간의 구 `Surface`**를 넘겼습니다. `SurfaceView.setFixedSize`는 기존 Surface를
`surfaceDestroyed`로 파괴하고 새 Surface를 **비동기로** 만든 뒤 `surfaceCreated → surfaceChanged`를
다시 부릅니다. 즉 화면은 한 프레임 뒤 죽는 표면을 출력 표면으로 붙잡은 셈이고, 두 창 모두 검게 남습니다.

- `surfaceFrame`이 이미 창 크기(`w×h`)와 같으면 **`setFixedSize`를 호출하지 않고** `pinned`만 기록합니다
  (`match_parent` SurfaceView는 레이아웃이 끝나면 이미 그 크기 → 불필요한 표면 파괴 자체가 없음).
- 크기가 달라 실제로 핀해야 할 때는 **핀한 뒤 즉시 `return`**. 새 표면이 `surfaceCreated`/`surfaceChanged`로
  도착한 다음 패스에서 그 안정된 표면으로 `create`/`setSurface`/`resize`를 합니다.
  같은 사각형에 대한 재시도는 2회로 제한(`MAX_PIN_ATTEMPTS`)해 표면 재생성 루프도 막습니다.
- `surfaceCreated`도 직접 표면을 붙이지 않고 **스케줄만** 합니다(같은 재진입 규칙).
- `SystemDisplayHost`·`LocalDisplayHost`의 `if (display.display == null) return` 가드 제거 — v4.0에는 없었고,
  그 속성은 `resize`/`setSurface`가 동작하는지와 무관합니다. 각 호출은 자체 `try/catch`로만 감쌉니다.

**진단**: 권한(`ADD_TRUSTED_DISPLAY`)이 없는데 SystemUID 빌드이거나, `create`가 실패하거나, 앱 실행이
예외를 내면 **창 위에 짧은 상태 문구**(예: `시스템 권한 미인식 · ADD_TRUSTED_DISPLAY 없음`)가 남고
`MNLauncher` 태그에 어느 경로(플랫폼 권한/Shizuku/자체 권한)였는지 로그가 찍힙니다. 정상일 때는 표시되지 않습니다.

### 2. 부팅 동영상 — 런처 자체 폴더 탐색기 (`util/BootFileBrowser.kt`)
- 선택 버튼 **짧게 누르면 바로 런처의 폴더 탐색기**가 열립니다. 펌웨어 기본 선택기는 다이얼로그의
  **`시스템 선택기`** 보조 버튼으로만 남겼습니다(그 선택기가 `LOCAL > Ts`만 보여 주는 기기이므로).
- 루트 목록: **내장 저장소** + `StorageManager.storageVolumes`(SD·USB, 이동식 표시) + `/storage/*` 및
  `/mnt/media_rw/*`에 마운트된 볼륨 + 런처의 `intro` 폴더.
- 폴더 탭 → 하위 폴더와 미디어 파일(`mp4·mkv·webm·3gp·avi·mov·m4v` / `jpg·jpeg·png·webp·bmp·gif·heic`)을
  나열하고 `⬑ 상위 폴더`로 자유롭게 이동. 목록은 워커 스레드에서 만들어 큰 `DCIM`에서도 멈추지 않습니다.
- `MANAGE_EXTERNAL_STORAGE`가 없으면 설정 화면으로 안내(거절해도 열리며 일부 폴더만 보임). SystemUID 빌드는
  `android.uid.system`이라 그대로 읽습니다. 고른 파일은 기존처럼 `files/intro/`로 **복사**해 적용합니다.
- `갤러리 색인` 버튼은 v4.2의 MediaStore+폴더 스캔 목록을 그대로 남긴 것입니다.

## 4.2 — v4.1 회귀 되돌림 · 왼쪽 창 리사이즈 · 부팅 로고 선택기

### 증상 정리 (실차 MEKEDE MN-L, 2026-11-26)
- v4.0(`62249b1`): 오른쪽 창 정상. 비율을 바꾸면 **왼쪽 창만 까맣게 굳음**.
- v4.1(`a97f67d`→`e820f19`): 설치 후 **양쪽 창 모두 아무것도 안 나옴**.
- 설정에서 부팅 동영상·사진을 고르면 **"선택기를 열 수 없습니다"**.

### v4.1이 왜 양쪽 다 지웠나 (v4.0 대비 바뀐 것만)
| v4.1이 바꾼 것 | 왜 창을 못 보이게 하는가 | v4.2 |
|---|---|---|
| `displayManager.getDisplay(existing) == null`이면 그 창을 `release` + 재생성 | AOSP `DisplayManagerService.getDisplayInfoInternal`은 `info.hasAccess(uid)` 또는 `isUidPresentOnDisplayInternal(uid, displayId)`일 때만 정보를 줍니다. 우리 창은 `OWN_DISPLAY_GROUP`(자기 디스플레이 그룹)이고 런처 자신은 그 화면 위에 창을 두지 않으므로, 펌웨어가 이 조건을 만족시키지 않으면 `getDisplay()`는 null입니다. 그러면 1.5초 스로틀마다 두 창을 모두 버리고 다시 만들고, `DESTROY_CONTENT_ON_REMOVAL` 때문에 재생성 때마다 그 화면의 앱까지 사라져 **영원히 까만 화면**이 됩니다. | 제거. 화면이 목록에 없다는 것은 "그리지 않는다"는 증거가 아닙니다. |
| `verify()`: 400ms 뒤 `getRealSize()`를 읽어 다르면 표면 재연결, 화면이 null이면 즉시 `drop` | 만든 창을 400ms 뒤 스스로 부수는 같은 문제. 위 항목과 겹쳐 "만들자마자 사라지는" 루프가 됩니다. | 로그만 남기고, 실제로 크기가 어긋난 경우에만 `resize` 1회 재적용(`drop`·`setSurface` 없음). |
| 생성 직후·`resume`마다 `setDisplayState(true)` | AOSP에서 이 호출은 장치의 `mIsDisplayOn`을 바꾸고 `DisplayDeviceInfo`를 무효화해 CHANGED 이벤트를 보냅니다. 생성 직후의 상태 변경은 펌웨어가 그 화면의 logical display를 다시 계산하게 만들 수 있습니다. v4.0은 한 번도 부르지 않고 오른쪽 창을 정상 출력했습니다. | 생성/리사이즈 경로에서 제거. 전체화면 freeze(`setPaneVisible`)에서만 사용. |
| 생성 시 `setSurface` → `resize` 순서로 교체 (원본 `La8/c0.b`를 따른 것) | 원본의 그 경로는 Flutter 텍스처 producer를 쓰는 다른 구현이고, 우리 네이티브 `SurfaceView` 경로에서 검증된 순서는 v4.0의 `resize` → `setSurface`입니다. | v4.0 순서로 복원(`SystemDisplayHost.create`, `ShizukuControlService.createDisplay`). |
| `SurfaceHolder.setFixedSize` 완전 제거 | v4.0은 이 호출로 창 버퍼를 창 사각형에 맞춰 두고 정상 출력했습니다. 제거하면 버퍼 크기를 뷰 레이아웃에만 맡기게 되어, 이 펌웨어에서 v4.0이 만들던 "버퍼 = 표시 크기" 조건이 사라집니다. | 되살리되 **스케줄된 패스에서, 사각형이 실제로 바뀔 때만 1회** 부릅니다. |

### v4.0에서 실제로 문제였던 두 가지 (v4.2가 고친 것)
1. **표면 콜백 안에서 표시 장치를 만졌습니다.** v4.0은 `surfaceChanged` 안에서 곧바로
   `setFixedSize`/`resize`를 호출했습니다. `SurfaceView`가 자기 표면을 갱신하는 도중에 그 표면의
   버퍼 크기를 다시 잡는 셈이라, 실차에서 **왼쪽 창이 비율 변경 뒤 굳었습니다**(오른쪽은 정상).
   v4.2는 콜백에서 **스케줄만** 하고, 실제 작업은 메인 루퍼의 다음 패스에서 합니다.
2. **출력 표면을 떼어냈습니다.** v4.0은 `surfaceDestroyed`에서 `setSurface(null)`을 불렀습니다.
   플랫폼 문서상 가상 화면의 출력 표면을 떼는 것은 "화면을 끄는 것"과 같고,
   `DESTROY_CONTENT_ON_REMOVAL`과 만나면 그 화면의 앱까지 정리되어 **다시 붙여도 돌아오지 않습니다**.
   v4.2는 표면이 사라져도 표시 장치와 앱을 그대로 두고, 새 표면이 오면 교체만 합니다.

그 밖에: 화면 재생성은 `resize`가 **연속 2회 거부**될 때만(그때는 앱이 이미 그 화면에서 사라진 상태),
진단용으로 `MNLauncher` 태그에 창 생성·리사이즈·표면 소멸·크기 리포트를 로그로 남깁니다.

### 부팅 로고 선택기 (v4.2)
증상: "사진/동영상 선택기를 열 수 없습니다". 원인은 `ActivityResultContracts.OpenDocument()`가
`ACTION_OPEN_DOCUMENT` **하나만** 시도한 것입니다. 이 기기에는 `DocumentsUI`가 없거나,
`android.uid.system`으로 도는 SystemUID 빌드의 호출을 펌웨어가 거부해 `ActivityNotFoundException`이 났습니다.

- `BootMediaPicker.launch`가 `ACTION_OPEN_DOCUMENT` → `ACTION_GET_CONTENT`(`*/*` + video/image) →
  `ACTION_PICK`(동영상, 사진) 순으로 시도합니다.
- 전부 실패하면 **런처 내장 목록**: 갤러리 MediaStore의 동영상·사진 + `Download·Movies·Pictures·DCIM·Music`
  폴더 직접 스캔(mp4·mkv·webm·3gp·avi·mov·m4v / jpg·jpeg·png·webp·bmp·gif·heic)을 한 다이얼로그로 보여 줍니다.
  선택 버튼을 **길게 누르면** 언제든 이 목록이 열립니다(선택기는 열리는데 아무것도 안 보이는 기기용).
- 고른 파일은 앱 전용 저장소(`files/intro/`)로 **복사**합니다. 부팅 로고는 어떤 URI 권한도 없는
  부팅 시점에 재생되어야 하므로 `content://` 핸들만으로는 다음 부팅에서 사라집니다.
- 매니페스트에 `READ_MEDIA_VIDEO`, `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE`(≤32),
  `MANAGE_EXTERNAL_STORAGE` 추가 — SystemUID 빌드는 설치 시, 표준 빌드는 실행 시 부여됩니다.

## 4.0 — 카카런처 가상화면 엔진 이식 · 이름/부팅로고/여백/테마 개편

사용자 요청(2026-11-26): 앱 이름 `BMW MN-Launcher`, 독수리 같은 로고 없는 부팅 화면 +
사용자 시작 동영상, 위아래 여백 없는 분할 화면, `완전블랙 / 완전블랙` 테마, 카카런처 가상화면 엔진 이식.

### 1. 카카런처 원본 APK 분석 결과 (dexdump)

`carcar-3.3.10-default`의 `classes.dex`(Flutter + 네이티브 Kotlin 플러그인)를
`build-tools/34.0.0/dexdump -d`로 풀어 확인한 사실입니다. 가상화면 관련 코드는
`com.example.virtual_display_views.VirtualDisplayViewsChannel`(Flutter 채널)과
`Lo1/*`, `La8/*`(VirtualDisplayManager), `Lw7/*`·`Lx7/*`·`Ly7/*`·`Lz7/*`(SurfaceView 기반 AppView),
`Lb8/*`, `Lv7/*`(설정 값)에 들어 있었습니다.

| 항목 | 원본 구현 | 이식 위치 |
|---|---|---|
| 가상화면 생성 | `DisplayManager.createVirtualDisplay(name, w, h, dpi, null, 0xD09)` — 표면 없이 만들고 나중에 붙임 | `SystemDisplayHost`, `ShizukuControlService.createDisplay` |
| 플래그 | `0xD09` = `PUBLIC`\|`OWN_CONTENT_ONLY`\|`DESTROY_CONTENT_ON_REMOVAL`\|`TRUSTED`\|`OWN_DISPLAY_GROUP` (3337) | 같은 상수, 약한 조합으로 순차 폴백 |
| 화면 정보 고정 | `IWindowManager.dontOverrideDisplayInfo(displayId)` — 생성 직후, 리사이즈 때마다 | `WindowService.dontOverrideDisplayInfo` |
| 크기/밀도 | `VirtualDisplay.resize(w, h, floor(densityDpi × scale))` — 비율 변경 시 실시간 | `VirtualDisplayEngine.sync` → `DisplayHost.resize` |
| 표면 | Flutter `TextureRegistry.SurfaceProducer.getSurface()` → `setSurface` / `setSurface(null)` | `SurfaceView.holder.surface` → `DisplayHost.setSurface` |
| 터치 | `MotionEvent.setDisplayId(id)` → `IWindowManager.syncInputTransactions(true)` → `InputManager.injectInputEvent(event, ASYNC)` → 다시 sync (다운/포인터다운 전, 업 제외) | `SystemTouchInjector`, `ShizukuControlService.injectTouch` |
| 터치(강화) | `IWindowManager.injectInputAfterTransactionsApplied(event, 0)` — 화면 트랜잭션 적용 후 주입 | 같은 곳에서 우선 시도 |
| 숨긴 창 | `VirtualDisplay.setDisplayState(false/true)` — release 대신 freeze | `DisplayHost.setState`, 전체화면 전환 |
| 앱 실행 | `ActivityOptions.setLaunchDisplayId(displayId)` + `FLAG_ACTIVITY_NEW_TASK` | `VirtualDisplayEngine.launchOnDisplay` |
| 키 입력 | `KeyEvent.setDisplayId(id)` 후 같은 주입 경로 (BACK) | `SystemTouchInjector.injectBack` |
| 디스플레이 감시 | `DisplayManager.registerDisplayListener` | (기존 `SurfaceHolder` 콜백 + resize로 갈음) |

원본 `Lw7/g`는 Flutter 텍스처 표면을 `SurfaceControl` 컨테이너 레이어로 감싸
(`IWindowSession.reparentDisplayContent`) 가상화면을 그 TextureView에 합성합니다.
이 프로젝트는 Flutter가 아니라 네이티브 View를 쓰므로, 같은 역할을 `SurfaceView.holder.surface`를
가상화면 출력 표면으로 직접 쓰는 방식으로 대체했습니다(합성 레이어 불필요).

원본은 `sharedUserId="android.uid.system"` + 플랫폼 서명으로 위 권한을 직접 갖습니다.
그래서 이 프로젝트도 **두 가지 APK**를 만듭니다.

- **`BMW-MN-Launcher.apk` (standard)** — `sharedUserId` 없음. `ADD_TRUSTED_DISPLAY`,
  `INJECT_EVENTS`, `MANAGE_ACTIVITY_TASKS`를 선언하지만 일반 설치는 허용되지 않으므로,
  화면 생성·터치 주입을 **Shizuku**(adb shell UID)로 수행하고, Shizuku가 없으면 접근성 제스처,
  그것도 없으면 자유창 안전 모드로 자동 전환합니다. 폴드8·일반 태블릿에 그대로 설치됩니다.
- **`BMW-MN-Launcher-SystemUID.apk` (systemuid)** — `sharedUserId="android.uid.system"` 포함.
  런처 프로세스가 곧 시스템 UID라 카카런처와 **완전히 같은 경로**로 동작합니다.
  실차(MEKEDE MN-L)에서 플랫폼 키로 서명된 ROM에만 설치됩니다(아래 주의점 참고).

### 2. 앱 이름 · 부팅 화면 (로고 제거 + 사용자 시작 동영상)

- 이름: **BMW MN-Launcher** (런처 목록·설정·홈 선택 화면 모두).
- **기본 부팅 화면에는 로고가 없습니다.** 카카런처의 독수리 스플래시를 재현하지 않고, 내장 M
  애니메이션도 기본값에서 뺐습니다. 시작 동영상/사진을 지정하지 않으면 곧바로 분할 화면이 뜹니다.
- **안드로이드 12+ 시스템 스플래시도 검정·아이콘 없음**으로 바꿨습니다(`Theme.BmwLauncher.Splash`).
  콜드 스타트 때 앱 아이콘이 잠깐 뜨던 것도 사라집니다.
- 설정 → 시작 및 복원 → **부팅 동영상·사진 선택**으로 mp4/webp/png/jpg를 고르면 그 파일이
  부팅 로고가 됩니다. 영상은 원본 비율 그대로 화면 가운데에서 재생되고(자르지 않음), 끝나면
  자동으로 분할 화면으로 넘어갑니다. 화면을 터치하면 즉시 건너뜁니다.
- `부팅 로고 없음`으로 지우거나, 원하면 `기본 M 인트로`를 명시적으로 고를 수 있습니다.

### 3. 분할 화면 여백 제거 (0dp · 직사각)

- 창 아래 **28dp 핸들 띠를 없앴습니다.** 앱 화면(SurfaceView)이 창 사각형을 **위아래 끝까지** 채웁니다.
- 핸들은 이제 **투명 오버레이**입니다. 창 하단 가장자리에 72×4dp 회색 알약 하나만 그리고,
  4초 뒤 28%로 흐려져 앱 화면을 가리지 않으며, 손이 닿으면 다시 밝아집니다.
  짧게 누르기·길게 누르기·위로 스와이프 모두 그대로 창 메뉴를 엽니다.
- 상태바·내비게이션 바는 몰입 모드로 항상 숨기고, 컷아웃도 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`로
  그 위까지 그립니다. 여백·모서리 반경 0dp, 배경 `#000000`.
- 자유창 안전 모드에서만 창 아래 16dp를 비워 핸들이 창에 가려지지 않게 했습니다(가상화면은 0dp).

### 4. 테마 — 완전블랙 / 완전블랙

- 설정 → 테마에 **완전블랙 / 완전블랙** 두 항목을 넣었습니다(기본: 첫 번째).
  둘 다 배경 `#000000`이고, 두 번째만 창 테두리 1dp를 없앤 완전 평면입니다.
- 창·독·오버레이·설정 화면 배경을 모두 `#000000`, 모서리 0dp로 통일했습니다.

### 5. 설치 주의점

- **standard APK**: 일반 폰/태블릿(폴드8 포함)에 그대로 설치됩니다. 가상화면 방식은 Shizuku 권한을
  허용해야 하며, 없으면 접근성 서비스(Android 11+)로 탭·스크롤만 되고, 그것도 없으면 자유창 모드로 뜹니다.
- **SystemUID APK**: `sharedUserId="android.uid.system"` 때문에 **플랫폼 키로 서명되지 않은 기기에서는
  설치 자체가 거부**됩니다(`Package … has no signatures that match those in shared user android.uid.system`).
  실차에서는 ① 플랫폼 키로 다시 서명(apksigner)하거나 ② 서명 검증을 끈 커스텀 ROM/루팅 환경에서만 설치하세요.
  두 APK는 패키지명이 달라(`com.hyteck.bmwf07launcher.systemuid`) 한 기기에 같이 설치할 수 있습니다.
- 앱 데이터(프리셋·설정)는 두 APK가 서로 공유하지 않습니다.


## 3.4 — 가상 디스플레이 + Shizuku 터치 주입 · 하단 회색 바 클릭 메뉴

사용자 결정(2026-11-24): "가상디스플레이방식으로 해야겠네" — 자유창 대신 **가상 디스플레이**를 기본으로.

- **가상 디스플레이 방식은 Shizuku가 필요합니다.** 안드로이드는 앱이 스스로 만든 가상 화면에 다른 앱을
  올리는 것을 거부합니다(`setLaunchDisplayId` → SecurityException, 자기 앱조차). 다른 앱을 올리려면 그 화면이
  `TRUSTED`여야 하고, 그 플래그는 `ADD_TRUSTED_DISPLAY`(signature|role) 권한이 있어야 합니다. 그래서 **화면 생성
  자체를 Shizuku의 shell 서비스에서** 합니다. 권한이 없으면 크래시 없이 자유창 안전 모드로 폴백합니다.
- **가상 디스플레이 창을 실제로 만질 수 있습니다.** 런처 안에 그린 두 앱은 터치를 받지 못했는데,
  이제 판의 `SurfaceView`가 받은 터치를 그 창의 가상 디스플레이로 넘깁니다.
  - 1순위 **Shizuku**: `ShizukuControlService`(adb shell UID 2000, `INJECT_EVENTS`)가
    `InputManager.injectInputEvent` + 이벤트 `displayId`로 다운·무브·업·멀티터치를 그대로 주입합니다(지연 없음).
  - 2순위 **접근성 서비스**(Shizuku 없이도): `GestureDescription.Builder.setDisplayId()`(Android 11+,
    공개 API)로 손가락이 지나간 경로를 손을 뗀 순간 그 디스플레이에 재생합니다(한 손가락).
  - 둘 다 없으면 예전처럼 **자유창 안전 모드**로 자동 폴백하며, 설정 화면에 Shizuku 상태
    (`연결됨 / 권한 필요 / 미실행`)와 **권한 요청** 버튼이 나옵니다.
- **하단 반투명 회색 바.** 각 창 아래 28dp 띠 안에 96×6dp 회색 pill(`#669E9E9E`, 누르면 밝아짐)을
  그렸습니다. **한 번 짧게 누르면** 그 창의 메뉴(`앱 변경·선택 / 전체화면 / 앱 빼기 / 화면 배율`)가
  바로 열립니다. 띠 전체가 터치 영역이고, 길게 누르기·위로 스와이프도 그대로 동작합니다.
- **가상 창은 `TRUSTED` 플래그를 먼저 시도**하고, 펌웨어가 거부하면 `PUBLIC|SUPPORTS_TOUCH`로 내려갑니다.
- 3.3의 동작(무한 재실행 방지, 풀스크린, 사진 인트로)은 그대로입니다.

설치 후: 설정 → Shizuku 권한 요청 → 허용. Shizuku가 없으면 접근성 서비스만 켜도 탭·스크롤이 됩니다.

## 3.3 — 분할 창 복구 · 닫은 앱이 다시 열리는 문제 수정

3.2에서 생긴 두 가지 회귀만 고쳤습니다. 3.2의 나머지 기능(완전 풀스크린, 20dp 핸들 길게 누르기,
인트로 사진)은 그대로입니다.

- **자유창 분할이 다시 50:50으로 열립니다.** 3.2에서 `FLAG_ACTIVITY_MULTIPLE_TASK`를 빼자 펌웨어가
  넘겨준 사각형을 무시하고 앱을 전체화면으로 열었습니다. 새 배치(`reorder = false`)에 3.1과 같은
  `MULTIPLE_TASK or RESET_TASK_IF_NEEDED` 플래그를 다시 붙였습니다. 이미 떠 있는 창을 올릴 때는
  그대로 `REORDER_TO_FRONT`만 씁니다.
- **닫은 앱이 저절로 다시 열리지 않습니다.** 3.2는 런처가 포커스를 받을 때마다(`onResume`) 무조건
  두 창을 다시 올렸기 때문에, 자유창의 `×`로 앱을 닫으면 곧바로 그 앱이 다시 실행되어 끝나지 않았습니다.
  이제 설정 화면·앱 서랍을 열기 직전에만 `resumeFromOwnScreen = true`로 두고, 그 화면에서 돌아왔을
  때만 창을 다시 올립니다. 사용자가 앱 창을 닫아 런처로 돌아온 경우에는 아무것도 실행하지 않습니다.

## 3.2 — 완전 풀스크린 · 1초 길게 누르기 · 인트로 사진

- **상태바·내비게이션 바를 항상 완전히 숨깁니다.** 3.1까지는 `systemUiVisibility` 플래그만 썼는데,
  LD플레이어와 일부 순정 헤드유닛에서는 시계·배터리 아이콘이 있는 상단 상태바가 그대로 남았습니다.
  이제 `WindowCompat.setDecorFitsSystemWindows(false)` + `WindowInsetsControllerCompat`(Immersive Sticky)로
  두 바를 숨기고, `onResume`과 `onWindowFocusChanged`에서 다시 적용합니다. 대화상자·키보드 때문에 바가
  돌아와도 런처가 포커스를 가진 순간 바로 사라집니다. (런처·설정·앱 서랍 세 화면 모두)
- **창 하단 핸들을 20dp로 키우고, 약 0.8초 길게 누르면 바로 그 창 메뉴가 열립니다.** 예전에는 플랫폼
  기본 롱클릭(약 0.5초, 손을 떼야 동작)에 기대고 있어 핸들이나 빈 창에서 메뉴가 늦게, 또는 클릭으로
  처리되었습니다. 이제 손가락이 0.8초 머무는 순간 짧은 햅틱과 함께 `앱 변경·선택 / 전체화면 / 앱 빼기`
  메뉴가 열리고, 짧은 탭·위로 스와이프도 그대로 동작합니다.
- **앱 창은 핸들 띠 위에서 끝납니다.** 자유창이 창 사각형 전체(핸들 20dp 포함)에 놓이던 3.1까지는
  앱이 떠 있는 동안 하단 핸들이 창에 가려져 메뉴를 열 수 없었습니다. 이제 엔진에 넘기는 사각형의
  아래를 핸들 높이만큼 줄여, 앱이 떠 있어도 핸들은 항상 런처 것이고 길게 누르기가 닿습니다.
- **메뉴 이름을 쓰는 말 그대로**: `앱 변경 · 선택`(빈 창은 `앱 선택`), `전체화면`(전체화면 중에는
  `전체화면 해제`), `앱 빼기`, `화면 배율`. 빈 창에는 `앱 빼기`를 넣지 않습니다.
- **`FLAG_ACTIVITY_MULTIPLE_TASK` 제거** (3.3에서 원복): 네이버지도·QQ음악HD처럼 한 태스크만 쓰는 앱에
  두 번째 태스크를 만들라고 요구하지 않으려던 변경이었지만, 사각형이 무시되고 앱이 전체화면으로
  열리는 회귀를 낳아 3.3에서 3.1 플래그로 되돌렸습니다.
- **인트로에 사진도 사용 가능**: 설정에서 `사진·동영상 선택`으로 이미지를 고르면 MIME 타입을 함께
  저장하고, 시작할 때 검은 화면 가운데에 원본 비율 그대로(`FIT_CENTER`, 자르지 않음) 약 2.5초 보여 준 뒤
  자동 종료합니다. 화면을 터치하면 즉시 넘어가고 안내 문구는 표시하지 않습니다.

## 3.1 — 독 침범 방지 · 인트로 원본 비율

- **독 위로 창이 덮지 않습니다.** 3.0은 독과 왼쪽 창 사이에 2dp만 두었는데, 자유창은 테두리·그림자가
  사각형 밖으로 몇 픽셀 더 그려져 왼쪽 창이 독 위를 살짝 덮었고, 독 버튼의 첫 터치를 그 창이 가져갔습니다.
  이제 독이 왼쪽에 있으면 `left >= 독 오른쪽 + 8dp`(최소 독 너비 + 8dp), 오른쪽에 있으면
  `right <= 독 왼쪽 - 8dp`로 잡고, 같은 여백을 엔진에서 한 번 더 확인한 뒤 창을 띄웁니다.
- **인트로 동영상은 원본 비율 그대로** 나옵니다. 화면 전체 뷰에 `setTransform` 행렬을 덮어쓰던 방식을
  없애고, 영상 원본 해상도를 받아 `min(부모너비/영상너비, 부모높이/영상높이)`로 계산한 픽셀 크기를
  **뷰 자체 크기**(`FrameLayout.LayoutParams`, `Gravity.CENTER`)로 지정합니다. 원본보다 크게 늘리지 않고,
  잘라내지도 않으며, 남는 좌우는 검은색입니다.

## 3.0 — 일반 앱 권한만으로 안정 동작 (Shizuku 불필요)

v2.1에서 `네이버지도`·`QQ음악HD`가 `응답하지 않음` 후 꺼진 원인은 **반복 `startActivity`**였습니다.
(비율 변경마다 좌/우 재실행 + repair 재실행 + onResume 재실행) 3.0은 이 반복을 전부 없앴습니다.

- **실행은 사용자 동작당 정확히 1회**: 앱 선택·프리셋 실행 때만 `setLaunchBounds(Rect)`로 한 번 실행하고,
  좌/우 사이는 350ms 간격을 둡니다.
- **드래그 중에는 다른 앱 창을 건드리지 않습니다.** 비율은 중앙선에서 손을 뗀 순간(또는 비율 프리셋을
  누른 순간) 한 번만 적용하고, 같은 사각형이면 아무 호출도 하지 않습니다. repair 반복은 삭제했습니다.
- **설정·앱 서랍에서 돌아올 때**: 앱을 다시 실행하지 않고 이미 떠 있는 창을 한 번만 앞으로 올립니다.
- 일반 앱 권한으로는 OS가 다른 앱의 창 크기를 직접 바꾸는 것을 막으므로, 기기가 자유창 사각형을
  받아주지 않으면 앱을 억지로 재실행하지 않고 그대로 둡니다(앱이 죽지 않습니다).
- 가상 디스플레이 방식은 `창별 배율`용 실험 기능으로 남겨 두었습니다. (가상 화면에 그린 앱은 터치가
  런처로 전달되어 실사용에는 자유창 모드를 권장합니다.)

## 화면 구조

```
┌────┬────────────────────────┬─┬────────────────────────┐
│독  │  왼쪽 창 (앱 또는 +)   │ │  오른쪽 창 (앱 또는 +) │
│시계│                        │ │                        │
│프리│                        │ │                        │
│셋  │                        │ │                        │
│+   │                        │ │                        │
│앱  │                        │ │                        │
│설정│                     ▬  │ │                     ▬  │  ← 투명 핸들 오버레이(0dp)
└────┴────────────────────────┴─┴────────────────────────┘
```

- **올블랙 + 0dp 직사각**: 배경 `#000000`, 모든 모서리 반경 0dp, 위아래 여백 0dp, 장식 없음.
- **좌측 슬림 독(56dp)**: 작은 시계·날짜 → 저장한 분할 프리셋 타일 → `+` 저장 →
  전체 앱 서랍 → 설정 → `‹` 숨기기. 숨기면 화면 왼쪽 가장자리의 얇은 선을 눌러 다시 꺼냅니다.
- **창 하단 얇은 핸들(18dp 오버레이)**: 앱 화면을 가리지 않는 투명 오버레이. 4초 뒤 흐려지고,
  탭하거나 약 0.8초 길게 누르면 그 창의 메뉴(앱 변경·선택 · 전체화면 · 앱 빼기 · 화면 배율)가 창 안에서 열립니다,
  전체화면에서는 우상단 `복귀` 칩이나 핸들 다시 길게 눌러 2분할로 돌아옵니다.
- **중앙 디바이더**: 평소에는 1px 선. 좌우로 끌면 5:5 ~ 2:8 (20~80%) 자유 조절,
  탭하면 `5:5 6:4 7:3 8:2 / 4:6 3:7 2:8 / 좌우 바꾸기` 팝업.

## 기능

- **빈 슬롯에서 시작**: 처음 설치하면 앱을 자동 실행하지 않고 양쪽 창에 `+ 앱 추가`가 뜹니다.
  (설정에서 '마지막 분할 상태 기억'이 켜져 있고 저장된 조합이 있을 때만 복원합니다.)
- **프리셋**: 지금 두 앱 + 비율을 독의 `+`로 저장. 타일을 탭하면 그 조합으로 전환,
  길게 누르면 실행/이름 변경/좌우 앱 변경/순서 이동/삭제. 최대 12개.
  설정의 `BMW 추천 6종 프리셋 불러오기` 버튼으로 네이버지도·티맵 × FLO·QQ MUSIC·ReVanced를
  한 번에 넣을 수 있습니다.
- **전체 앱 서랍**: 자주 쓰는 앱 고정(상단 섹션), 앱 숨기기/복원, 탭=전체화면 실행,
  길게 누르면 좌/우 창에서 열기.
- **두 가지 실행 엔진**
  1. `가상 디스플레이` — 런처 안에 두 앱을 직접 렌더링. 비율·창별 배율이 즉시 반영됩니다.
  2. `시스템 분할/자유 창` — `ActivityOptions.setLaunchBounds`로 실제 앱 창을 배치합니다.
- **창별 앱 화면 배율(120~360 dpi, 기본 200)**: 창 아래 핸들 메뉴나 설정에서 왼쪽·오른쪽 창의 앱 UI 배율을 따로 맞춥니다.
  가상 디스플레이 전용이며, 시스템 자유창에서 배율을 바꾸면 안내와 함께 가상 디스플레이로 전환해 적용합니다.
- **런처 화면 크기(65~130%)**: 설정·앱 선택기·앱 서랍의 글자와 아이콘 크기를 한 번에 조절합니다.
- **음악 앱 자동 재생**: 설정에서 음악 앱을 지정하고 켜 두면, 그 앱을 창·프리셋·부팅 복원으로 열 때 약 1.5초 뒤
  미디어 PLAY 키를 보내 재생을 시작합니다.
- **빈 창은 `+ 앱 추가` 하나**: "터치하면 다시 엽니다" 같은 안내 문구 없이 + 버튼만 보이고, 누르면 창 안에서 앱 목록이 열립니다.
- **창 안에서 열리는 메뉴**: 창 아래 핸들이나 `+ 앱 추가`를 누르면 앱 목록·메뉴가 **그 창 안에서만** 열립니다(별도 다이얼로그 창이 아니라).
  왼쪽 창 메뉴는 왼쪽 창 밖으로 나가지 않으므로, 오른쪽 창 메뉴가 옆의 자유창 앱에 가려지지 않습니다.
- **시작/복원**: 부팅 시 자동 실행(`RECEIVE_BOOT_COMPLETED`), 마지막 좌/우 앱·비율·배율 복원.
  모든 설정은 `SharedPreferences.commit()`으로 즉시 기록해 시동이 급히 꺼져도 유지됩니다.
- **부팅 로고(선택)**: 직접 고른 동영상·사진을 시작 화면으로 씁니다. 지정하지 않으면 로고 없이 바로 시작합니다.
  영상은 원본 비율을 유지한 채 화면 가운데에 맞춰 재생하고, 남는 좌우는 올블랙이며, 위에 안내 문구를 띄우지 않습니다(터치하면 스킵).
  `TextureView`로 재생하며 2560×720에서도 비율이 틀어지지 않고, 끝나면 자동으로 분할 화면으로 넘어갑니다.

## 2.1에서 고친 것 (2026-11-24 저녁)

- **창 안에서만 열리는 메뉴**: `AppPickerDialog`/`PaneSheet`를 Dialog 창에서 각 창(`leftPane`/`rightPane`) 내부 오버레이로 교체.
  Dialog는 별도 창이라 자유창 앱(네이버지도)에 가려졌고, 화면 가운데에 걸쳐 떴습니다. 지금은 누른 창 안에서만 뜨고,
  2:8 좁은 창에서는 한 줄 버튼·작은 글자·패키지명 숨김으로 자동 전환됩니다.
- **양쪽 창이 함께 리사이즈**: 시스템 자유창에서 두 앱의 bounds를 같은 프레임에 보내면 하나만 적용되던 문제를 stagger(240ms)+재적용(repair)으로 고쳤습니다.
  가상 디스플레이는 좌/우 display를 같은 패스에서 `setSurface`(표면이 바뀐 경우)+`resize` 하고 220ms 뒤 한 번 더 확인합니다.
- **설정/앱 서랍을 다녀와도 앱 유지**: `onResume`에서 활성 엔진의 `resume()`으로 두 앱(또는 전체화면 앱)을 현재 bounds 그대로 다시 전면에 올립니다.
- **화면 튐 제거**: 동일 bounds는 한 번만 적용하고, 런처 내 화면 전환은 `overridePendingTransition(0,0)`·`FLAG_ACTIVITY_NO_ANIMATION`으로 애니메이션을 없앴습니다.

## 2.0에서 고친 리사이즈 버그

> 증상: 분할바를 움직이면 선만 움직이고 앱은 그대로이거나, 한 번 줄어든 창이 다시 커지지 않음.

- **가상 디스플레이**: 비율이 바뀔 때마다 `VirtualDisplay`를 release/recreate하던 코드를 없앴습니다.
  이제 `SurfaceHolder.setFixedSize(w,h)` → `VirtualDisplay.resize(w,h,dpi)` 순서로 크기를 맞추고,
  같은 display id로 `FLAG_ACTIVITY_REORDER_TO_FRONT` 재호출해 앱이 새 창 크기로 다시 배치되게 합니다.
  4초 주기 워치독이 실제 뷰 크기와 display 크기를 비교해 놓친 콜백도 스스로 고칩니다.
- **일시정지 복귀**: `surfaceDestroyed`에서 display를 release하지 않고 `display.surface = null`만 한 뒤,
  돌아오면 `surfaceCreated`에서 새 surface를 다시 붙이고 resize + 재실행합니다.
  (예전에는 런처를 나갔다 오면 창이 비었습니다.)
- **레이아웃**: `layout_weight` 대신 비율을 직접 계산하는 `SplitLayout`으로 바꿔, 줄였다 늘려도
  두 창이 항상 새 폭으로 다시 measure/layout됩니다. 모든 레이아웃 변경은
  `OnLayoutChangeListener` → `engine.applyBounds(측정된 사각형)`로 엔진에 전달됩니다.
- **시스템 분할**: 비율 변경 시 실행 중인 앱을 새 `Rect`로 `setLaunchBounds` + `REORDER_TO_FRONT`
  재호출해 실제 창 크기를 따라오게 했습니다.

## 빌드

로컬: JDK 17 + Android SDK 34 + Gradle 8.7

```
gradle assembleDebug     # 두 APK(standard, systemuid) 모두 빌드
gradle lintDebug         # 두 debug 변형 lint (오류 0건)

gradle assembleStandardDebug    # 표준 APK만
gradle assembleSystemuidDebug   # SystemUID APK만
```

산출물:
- `app/build/outputs/apk/standard/debug/app-standard-debug.apk`
- `app/build/outputs/apk/systemuid/debug/app-systemuid-debug.apk`

GitHub Actions로 뽑을 때는 저장소 루트의 `GITHUB_ACTIONS_BUILD.yml` 내용을
`.github/workflows/build.yml`로 복사해 Actions에서 실행하면 됩니다.

## 첫 실행 설정

1. 설정(⚙) → **기본 런처로 설정**에서 이 앱을 홈으로 지정합니다.
2. 설정(⚙) → **부팅 시 자동 실행**을 켜 둡니다(홈 지정 시에는 시스템이 직접 띄웁니다).
3. 분할 방식 선택
   - `가상 디스플레이`: 그대로 사용. 펌웨어가 막으면 안내 후 시스템 분할로 자동 전환됩니다.
   - `시스템 분할`: **접근성 서비스**를 켜고, 개발자 옵션의 `자유 형식 창`(freeform)을 켭니다.
4. 독이 비어 있으면 설정에서 **BMW 추천 6종 프리셋 불러오기**를 누르거나,
   창의 `+ 앱 추가`로 앱을 넣고 독의 `+`로 저장합니다.

## 알려진 제약

- 가상 디스플레이 모드는 기기 펌웨어가 서드파티 앱의 가상 화면 실행을 허용해야 합니다.
  막혀 있으면 안내 후 시스템 분할로 바뀌며, 이때는 앱 자체 배율은 바꿀 수 없습니다.
- 시스템 분할 모드에서 다른 앱의 창 크기를 바꾸려면 그 앱을 다시 앞으로 불러야 합니다
  (안드로이드가 일반 앱의 창 이동을 막기 때문). 비율을 크게 바꾼 뒤 앱이 옛 크기로 보이면
  해당 창 핸들을 한 번 눌러 다시 열면 새 크기로 배치됩니다.
- YouTube ReVanced는 패키지명에 `revanced`가 들어간 앱을 자동으로 찾습니다.
