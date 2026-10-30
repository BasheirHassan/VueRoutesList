<script setup lang="ts">
defineProps<{ id: string }>()
</script>

<template>
  <section>
    <h1>User {{ id }}</h1>
    <p>Nested route at <code>/admin/users/:id</code>.</p>
  </section>
</template>
