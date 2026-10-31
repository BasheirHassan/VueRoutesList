# Vue Routes — إضافة WebStorm

إضافة (Plugin) لـ WebStorm تعرض **كل مسارات Vue Router** الموجودة في المشروع داخل نافذة أداة واحدة، مع بحث فوري، والتنقل إلى تعريف المسار أو إلى مكوّن Vue المرتبط به.

> الإضافة مكتوبة بلغة Kotlin على IntelliJ Platform، ولا تحتاج إلى أي إعداد خاص داخل مشروع Vue. تكتشف ملفات التوجيه تلقائيًا.

---

## المزايا

- عرض جميع مسارات Vue Router في جدول منظم:
  - المسار الكامل (بما في ذلك المسارات المتداخلة `children`)
  - الاسم `name`
  - المكوّن `component` (مع حل `import()` والاختصارات)
  - ملف التوجيه ورقم السطر
- بحث ذكي فوري في:
  - المسار، الاسم، المكوّن، التوجيه `redirect`
  - `alias` و`meta.title`
  - اسم ملف التوجيه
  - المسارات الكاملة أو روابط URL كاملة، مع ترتيب النتائج حسب الصلة
- البحث يفهم معاملات المسار مثل `:id` و`:pathMatch(.*)*` وروابط hash.
- النقر المزدوج على أي مسار يفتح تعريفه مباشرة في ملف الـ router.
- النقر المزدوج على عمود **Component** يفتح ملف مكوّن Vue.
- قائمة سياق: فتح تعريف المسار، فتح المكوّن، فتح مكوّن المسار الأب (Layout)، فتح المكوّن الأساسي (أبعد Layout في سلسلة التعشيش)، نسخ المسار.
- التنقل إلى المكوّن الأب/الأساسي يعمل أيضًا عندما يكون المكوّن مغلّفًا بدالة مساعدة مثل `pageComponent('Accounts/Index')`.
- التحديث التلقائي عند حفظ أو تعديل ملفات التوجيه، أو إضافة/حذف مكوّنات Vue، أو تغيير `tsconfig`/`jsconfig` (قابل للإيقاف من الإعدادات).
- زر Refresh لإعادة الفحص يدويًا.
- دعم:
  - Vue Router 4 (`createRouter`)
  - Vue Router 3 (`new VueRouter`)
  - تعريفات `routes` المكتوبة بـ TypeScript (`RouteRecordRaw[]`)
  - `children` المتداخلة
  - `() => import('...')` و`defineAsyncComponent`
  - خرائط `import.meta.glob` (مثل `component: pages['./pages/Create.vue']`)
  - `components: { default: ... }`
  - `redirect` و`alias` (نص أو مصفوفة)
  - `component: pageComponent('Accounts/Index')` والدوال المساعدة التي تعيد مكوّنًا
  - اختصارات `@/` و`~/` و`paths` من كل ملفات `tsconfig*.json` / `jsconfig*.json` في الجذر
  - ملفات تصدّر مصفوفة مسارات مباشرة (`export default [...]`)
  - المصفوفات المدمجة عبر `...publicRoutes` داخل نفس الملف أو من ملف آخر (`...accountsRoutes`)
  - تمرير المصفوفة بالاسم إلى `createRouter({ routes: appRoutes })`
  - مصفوفة `children` المخزّنة في متغير داخل نفس الملف أو مستوردة من ملف آخر
  - `meta.layout` للعثور على `GuestLayout.vue` وأمثاله عند غياب Layout داخل شجرة المسارات

---

## المكوّن الأب والمكوّن الأساسي

عند الضغط بزر الماوس الأيمن على صف:

| العنصر | ما يفتحه |
|---|---|
| **Open Component** | مكوّن الصف نفسه (`component`) |
| **Open Parent Component** | مكوّن أقرب مسار أب يملك مكوّنًا، مثل `RouterPassthrough.vue` أو `AdminLayout.vue` |
| **Open Base Component** | أعلى مكوّن/تخطيط في سلسلة التعشيش، ويظهر فقط عندما يختلف عن المكوّن الأب |

يُعطَّل العنصر عندما لا يوجد ملف مطابق. أما صفوف المجموعات التي لا تملك `component` فسيبقى **Open Parent Component** و**Open Base Component** مفعّلين إن كان للمسار الأب مكوّن.

---

## البحث الذكي

حقل البحث يتعامل مع ما تكتبه كما لو كان رابطًا في المتصفح، وليس مجرد بحث نصي جزئي:

| الإدخال | ما يحدث |
|---|---|
| `/dashboard/employees` | يظهر المسار المطابق تمامًا أولًا، ثم المسارات الأبناء مثل `/dashboard/employees/create` و`/dashboard/employees/:id` |
| `/dashboard/employees/create` | المطابقة التامة أولًا، ثم المطابقات الديناميكية إن وُجدت |
| `/dashboard/employees/42` | يطابق `/dashboard/employees/:id` |
| `/dashboard/employees/42/edit` | يطابق `/dashboard/employees/:id/edit` |
| `https://app.com/dashboard/employees?tab=1#team` | يتجاهل النطاق و`?query` و`#fragment` |
| `http://localhost:5173/#/dashboard/employees` | يدعم روابط hash mode |
| `path: '/dashboard/employees'` | يتجاهل `path:` والاقتباس |
| `/dashboard/employees/` | يتجاهل الشرطة الأخيرة |
| `/employees` | يطابق مقطع المسار داخل مسار أطول |
| `dashboard create` | كل الكلمات يجب أن تظهر (AND)، بغض النظر عن الترتيب |
| `EmployeeCreate` | يبحث أيضًا في أسماء المكوّنات وملفاتها |

الترتيب حسب الصلة:

1. تطابق تام للمسار.
2. تطابق تام لأحد `alias`.
3. مسار يبدأ بالاستعلام (أبناء حرفيون).
4. مطابقة ديناميكية كاملة (مثل `:id`).
5. مطابقة ديناميكية جزئية.
6. مطابقة في منتصف المسار.
7. مطابقة نصية في المسار أو الاسم أو المكوّن أو `alias` أو `redirect` أو `meta.title` أو ملف التوجيه.

البحث غير حساس لحالة الأحرف، ويدعم `:id` و`:id?` و`:id(\\d+)` و`:pathMatch(.*)*` و`*`. أما المسار الشامل الذي لا يحتوي أي مقطع ثابت (مثل `/:pathMatch(.*)*`) فيُعرض في آخر النتائج حتى لا يزاحم المسارات المحددة.

---

## التثبيت

### 1. بناء الإضافة

من جذر المشروع:

```powershell
.\gradlew.bat buildPlugin "-PwebstormPath=C:\Program Files\JetBrains\WebStorm 2026.2.1"
```

- إذا لم تمرر `-PwebstormPath`، ستحاول الإضافة تنزيل WebStorm 2026.2.1 من مستودعات JetBrains.
- ملف التثبيت الناتج:

```
build/distributions/VueRoutes-1.2.2.zip
```

### 2. تثبيت الملف في WebStorm

1. افتح **Settings / Preferences → Plugins**.
2. اضغط علامة الترس ⚙ بجانب **Installed**.
3. اختر **Install Plugin from Disk...**.
4. اختر ملف `VueRoutes-1.2.2.zip`.
5. أعد تشغيل WebStorm.

### 3. الاستخدام

1. افتح مشروع Vue يحتوي على `vue-router`.
2. من القائمة: **View → Tool Windows → Vue Routes**.
3. ستظهر جميع المسارات في الجدول. اكتب في حقل البحث اسمًا أو مسارًا كاملًا مثل `/dashboard/employees`.

---

## اختصارات وطريقة الاستخدام

| الإجراء | الطريقة |
|---|---|
| التركيز على البحث | `Ctrl+F` |
| البحث بالكتابة المباشرة | ابدأ بكتابة أول حرف والجدول في المقدمة |
| مسح البحث | `Esc` |
| فتح أفضل نتيجة | `Enter` داخل حقل البحث |
| الانتقال إلى الجدول | `↓` داخل حقل البحث |
| فتح تعريف المسار | نقر مزدوج على الصف أو `Enter` |
| فتح المكوّن | نقر مزدوج على عمود Component أو `Shift+Enter` |
| فتح مكوّن المسار الأب (Layout) | زر الماوس الأيمن → **Open Parent Component** |
| فتح المكوّن الأساسي (أبعد Layout في السلسلة) | زر الماوس الأيمن → **Open Base Component** |
| نسخ المسار | قائمة السياق → Copy Route Path |
| إعادة الفحص | زر Refresh في شريط الأدوات |

---

## الإعدادات

**Settings → Tools → Vue Routes**

- **Auto refresh**: إعادة الفحص تلقائيًا بعد تعديل ملفات التوجيه `.js`/`.ts`، أو إضافة/حذف ملفات `.vue`، أو تغيير إعدادات TypeScript.
- **Excluded folders**: مجلدات مستبعدة من الفحص، مفصولة بفواصل. الافتراضي يشمل `node_modules` و`dist` و`build` وغيرها.
- **Maximum file size**: تجاهل ملفات التوجيه الأكبر من حد معين.

---

## المتطلبات

- WebStorm 2025.1 أو أحدث (تم البناء والتشغيل مقابل WebStorm المثبّت، build 262.10968.77).
- JDK 21+ لبناء الإضافة (يُفضّل JDK المرفق مع WebStorm).
- Gradle غير مطلوب تثبيته يدويًا؛ يوجد Gradle Wrapper داخل المشروع.

---

## التطوير

```powershell
# تشغيل WebStorm تجريبيًا مع الإضافة
.\gradlew.bat runIde "-PwebstormPath=C:\Program Files\JetBrains\WebStorm 2026.2.1"

# اختبارات المحلل والبحث الذكي
.\gradlew.bat test "-PwebstormPath=C:\Program Files\JetBrains\WebStorm 2026.2.1"

# بناء ملف التوزيع
.\gradlew.bat buildPlugin "-PwebstormPath=C:\Program Files\JetBrains\WebStorm 2026.2.1"
```

مثال Vue جاهز للتجربة موجود في:

```
examples/vue-routes-demo/
```

افتح هذا المجلد كمشروع مستقل في WebStorm ثم شغّل نافذة **Vue Routes**.

### بنية المشروع

```
src/main/kotlin/com/veuroutes/
├── model/
│   ├── VueRoute.kt                # نموذج المسار المعروض
│   └── SearchableRoute.kt         # الواجهة التي يستخدمها محرك البحث
├── parser/
│   ├── RouteParser.kt             # محلل JS/TS خفيف لاستخراج مصفوفة routes
│   └── ImportCollector.kt         # ربط أسماء المكوّنات بمسارات الاستيراد
├── search/RouteSearch.kt          # البحث الذكي وترتيب النتائج حسب الصلة
├── scan/
│   ├── VueRoutesScanner.kt        # مسح المشروع وتجميع المسارات بين الملفات
│   ├── AliasResolver.kt           # حل @/ و~/ وtsconfig paths
│   ├── TsConfigLoader.kt          # قراءة tsconfig*.json / jsconfig*.json وextends
│   ├── ComponentResolver.kt       # حل مكوّنات import وhelper calls وmeta.layout
│   └── ComponentHints.kt          # قواعد أسماء ملفات المكوّنات (قابلة للاختبار)
├── service/VueRoutesService.kt    # التخزين المؤقت، الخلفية، والتحديث التلقائي
├── settings/                       # إعدادات الإضافة وواجهتها
└── ui/                             # نافذة الأداة، الجدول، وأدوات التنقل
```

### لماذا لا نستخدم JavaScript PSI؟

محلل المسارات مكتوب بشكل مستقل عن واجهات JavaScript في WebStorm. هذا يجعل الإضافة:

- أسرع في الفحص،
- أخف في الاعتماديات،
- قابلة للعمل في WebStorm والإصدارات المختلفة دون ربط بإصدار محرك JavaScript.

في المقابل، لا تُنفَّذ JavaScript فعليًا؛ تُستخرج القيم الحرفية والاستيرادات الشائعة فقط.

---

## القيود المعروفة

- المسارات المبنية ديناميكيًا في وقت التشغيل (دوال تُرجع مصفوفة، أو بيانات من API) لا يمكن استخراجها.
- يُدمج الملف المستورد عندما يُصدَّر منه متغير واضح ويُستخدم بـ `...name`؛ أما بناء المسارات بدوال مثل `createRoutes()` فلا يُنفَّذ.
- في المشاريع متعددة الحزم (monorepo) تُقرأ ملفات `tsconfig*.json` / `jsconfig*.json` من جذر المشروع فقط؛ اختصارات الحزمة الفرعية قد لا تُحل إلى ملفات، ويظل اسم الاستيراد ظاهرًا في الجدول.
- اختصارات Vite/Custom aliases غير الموجودة في `tsconfig` قد لا تُحل إلى ملفات؛ في هذه الحالة يحاول حل المكوّنات مطابقة المسار داخل مجلدات `Pages` و`Views` و`Layouts` و`Components`.
- يعتمد اكتشاف ملفات التوجيه على محتوى الملف؛ قد تظهر مصفوفة أخرى تشبه كائنات المسارات (`path` + `component`) كمسارات.
- التوجيه المعتمد على نظام الملفات (مثل `unplugin-vue-router` أو Nuxt pages) غير مدعوم حتى الآن؛ الإضافة تستهدف ملفات إعداد `vue-router`.

---

## الرخصة

MIT — راجع ملف [LICENSE](LICENSE).
