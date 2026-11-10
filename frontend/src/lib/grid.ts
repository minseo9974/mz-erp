/**
 * AG Grid 진입점. 그리드를 쓰는 화면은 ag-grid-react 대신 여기서 가져온다.
 * 모듈 등록을 여기 두어야 AG Grid(약 1MB)가 메인 번들이 아니라 그리드 화면 청크로만 들어간다.
 */
import {
  CellStyleModule,
  CheckboxEditorModule,
  ClientSideRowModelModule,
  ModuleRegistry,
  TextEditorModule,
  ValidationModule,
} from 'ag-grid-community'

// 쓰는 기능만 등록한다. 빠진 모듈을 쓰면 개발 모드에서 ValidationModule이 콘솔에 알려 준다.
ModuleRegistry.registerModules([
  ClientSideRowModelModule,
  TextEditorModule,
  CheckboxEditorModule,
  CellStyleModule,
  ...(import.meta.env.DEV ? [ValidationModule] : []),
])

export { AgGridReact } from 'ag-grid-react'
export type { ColDef } from 'ag-grid-community'
