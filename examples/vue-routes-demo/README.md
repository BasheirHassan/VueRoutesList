# Vue Routes List Demo

مشروع Vue 3 + Vue Router صغير لاختبار إضافة **Vue Routes List**.

```bash
npm install
npm run dev
```

افتح هذا المجلد كمشروع مستقل في WebStorm، ثم:

**View → Tool Windows → Vue Routes List**

يحتوي المشروع على:

- مسارات جذرية وبسيطة
- مسارات متداخلة تحت `/admin`
- استيراد كسول `import()`
- `redirect`
- `alias` كمصفوفة
- `meta.title`
- مسار التقاط الكل `/:pathMatch(.*)*`
- ضم مصفوفة مسارات محلية باستخدام `...publicRoutes`
