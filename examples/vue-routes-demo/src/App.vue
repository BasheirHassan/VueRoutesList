<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <header>
    <strong>Vue Routes Demo</strong>
    <nav>
      <RouterLink to="/">Home</RouterLink>
      <RouterLink to="/about">About</RouterLink>
      <RouterLink to="/admin">Admin</RouterLink>
      <RouterLink to="/login">Login</RouterLink>
    </nav>
  </header>

  <main>
    <RouterView />
  </main>
</template>

<style>
body {
  margin: 0;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  color: #24313d;
}
header {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 14px 24px;
  background: #35495e;
  color: #fff;
}
header strong {
  color: #7bd3ab;
}
nav {
  display: flex;
  gap: 14px;
}
nav a {
  color: #dbe4ed;
  text-decoration: none;
}
nav a.router-link-active {
  color: #7bd3ab;
  font-weight: 600;
}
main {
  max-width: 920px;
  margin: 40px auto;
  padding: 0 24px;
}
</style>
