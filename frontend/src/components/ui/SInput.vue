<script setup>
import { computed, ref } from 'vue'

defineOptions({ inheritAttrs: false })

const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  type: { type: String, default: 'text' },
  size: { type: String, default: 'md' },
  showPassword: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  rows: { type: [Number, String], default: 2 }
})

const emit = defineEmits(['update:modelValue'])

const focused = ref(false)
const passwordVisible = ref(false)

const isTextarea = computed(() => props.type === 'textarea')
const actualType = computed(() => {
  if (props.type === 'password') return passwordVisible.value ? 'text' : 'password'
  return props.type
})

function onInput(e) {
  emit('update:modelValue', isTextarea.value ? e.target.value : e.target.value)
}
</script>

<template>
  <div
    class="s-input"
    :class="[
      `s-input--${size}`,
      { 'is-focused': focused, 'is-disabled': disabled, 'is-textarea': isTextarea }
    ]"
  >
    <textarea
      v-if="isTextarea"
      class="s-input__field"
      :value="modelValue"
      :disabled="disabled"
      :rows="rows"
      v-bind="$attrs"
      @input="onInput"
      @focus="focused = true"
      @blur="focused = false"
    />
    <template v-else>
      <input
        class="s-input__field"
        :type="actualType"
        :value="modelValue"
        :disabled="disabled"
        v-bind="$attrs"
        @input="onInput"
        @focus="focused = true"
        @blur="focused = false"
      />
      <button
        v-if="type === 'password' && showPassword"
        type="button"
        class="s-input__eye"
        :aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
        tabindex="-1"
        @click="passwordVisible = !passwordVisible"
      >
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
          <path d="M3.2 12s3.1-5.2 8.8-5.2 8.8 5.2 8.8 5.2-3.1 5.2-8.8 5.2S3.2 12 3.2 12Z" />
          <circle cx="12" cy="12" r="2.5" />
        </svg>
      </button>
    </template>
  </div>
</template>

<style scoped>
.s-input {
  display: flex;
  align-items: center;
  width: 100%;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  transition: border-color var(--dur-1) var(--ease), box-shadow var(--dur-1) var(--ease);
  min-width: 0;
}

.s-input:hover:not(.is-disabled) {
  border-color: var(--border-strong);
}

.s-input.is-focused {
  border-color: var(--primary);
  box-shadow: var(--ring);
}

.s-input.is-disabled {
  background: var(--surface-3);
  opacity: .7;
  pointer-events: none;
}

.s-input.is-textarea {
  align-items: stretch;
}

.s-input__field {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--text-1);
  padding: 0 12px;
  font-size: var(--fs-body);
}

.s-input__field::placeholder {
  color: var(--text-4);
}

.s-input--md .s-input__field {
  height: 34px;
}

.s-input--lg .s-input__field {
  height: 42px;
  font-size: 14.5px;
}

.is-textarea .s-input__field {
  height: auto;
  padding: 9px 12px;
  resize: vertical;
  line-height: 1.55;
}

.s-input__eye {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: 32px;
  height: 30px;
  margin-right: 4px;
  border: 0;
  background: transparent;
  color: var(--text-4);
  border-radius: var(--r-sm);
  transition: color var(--dur-1) var(--ease), background var(--dur-1) var(--ease);
}

.s-input__eye:hover {
  color: var(--text-2);
  background: var(--surface-3);
}
</style>
