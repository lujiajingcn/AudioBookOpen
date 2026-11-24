"""积分充值弹窗：套餐列表 + 支付渠道 + 下单（展示支付链接 / 二维码占位）。"""
from __future__ import annotations

import threading
from typing import Optional, List

from PySide6.QtCore import Signal, QObject, Qt
from PySide6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QListWidget, QListWidgetItem, QLabel,
    QComboBox, QPushButton, QTextEdit, QGroupBox, QWidget,
)

from app.core.api_client import ApiClient
from app.models.models import CreditPackage


class _Bridge(QObject):
    packages = Signal(object)   # List[CreditPackage]
    channels = Signal(object)   # List[dict]
    order = Signal(object)      # dict
    error = Signal(str)


class RechargeDialog(QDialog):
    """充值流程对话框。

    信号:
        credits_changed() —— 下单成功后发出，通知外部刷新余额。
    """

    credits_changed = Signal()

    def __init__(self, api: ApiClient, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.api = api
        self._pkgs: List[CreditPackage] = []
        self.setWindowTitle("积分充值")
        self.setMinimumSize(440, 520)

        self._b = _Bridge()
        self._b.packages.connect(self._on_packages)
        self._b.channels.connect(self._on_channels)
        self._b.order.connect(self._on_order)
        self._b.error.connect(lambda m: self.status.setText(f"加载失败：{m}"))

        lay = QVBoxLayout(self)

        lay.addWidget(QLabel("选择套餐："))
        self.pkg_list = QListWidget(self)
        self.pkg_list.itemSelectionChanged.connect(self._update_create_enabled)
        lay.addWidget(self.pkg_list, 2)

        chg = QGroupBox("支付渠道")
        chlay = QHBoxLayout(chg)
        chlay.addWidget(QLabel("渠道："))
        self.channel_box = QComboBox(self)
        chlay.addWidget(self.channel_box, 1)
        lay.addWidget(chg)

        self.create_btn = QPushButton("创建订单")
        self.create_btn.clicked.connect(self._create_order)
        self.create_btn.setEnabled(False)
        lay.addWidget(self.create_btn)

        self.status = QLabel("加载中…")
        self.status.setStyleSheet("color:#666;")
        lay.addWidget(self.status)

        self.order_view = QTextEdit(self)
        self.order_view.setReadOnly(True)
        self.order_view.setPlaceholderText("订单 / 支付信息将显示在此处（二维码占位或支付链接）")
        self.order_view.setMaximumHeight(180)
        lay.addWidget(self.order_view)

        close = QPushButton("关闭")
        close.clicked.connect(self.reject)
        lay.addWidget(close)

        self._load()

    # ---------- 数据加载 ----------

    def _load(self):
        b = self._b

        def _fetch():
            errors = []
            pkgs = channels = None
            try:
                pkgs = self.api.get_credit_packages()
            except Exception as e:  # noqa: BLE001
                errors.append(f"套餐: {e}")
            try:
                channels = self.api.get_payment_channels()
            except Exception as e:  # noqa: BLE001
                errors.append(f"渠道: {e}")
            if pkgs is None:
                b.error.emit("; ".join(errors) or "无数据")
            else:
                b.packages.emit(pkgs)
            b.channels.emit(channels or [])

        threading.Thread(target=_fetch, daemon=True).start()

    def _on_packages(self, pkgs):
        self._pkgs = list(pkgs or [])
        self.pkg_list.clear()
        for p in self._pkgs:
            it = QListWidgetItem(f"{p.name}  —  {p.credits} 积分 / ￥{p.amount:.2f}")
            it.setData(Qt.UserRole, p.id)
            self.pkg_list.addItem(it)
        self.status.setText(f"共 {len(self._pkgs)} 个套餐" if self._pkgs else "暂无可用套餐")

    def _on_channels(self, channels):
        self.channel_box.clear()
        for ch in (channels or []):
            code = ch.get("code") or ch.get("channel") or ch.get("id") or ""
            name = ch.get("name") or ch.get("displayName") or code
            if code:
                self.channel_box.addItem(name, code)
        if not self.channel_box.count():
            self.channel_box.addItem("默认", "default")
        self._update_create_enabled()

    def _selected_package_id(self) -> int:
        items = self.pkg_list.selectedItems()
        if not items:
            return 0
        return int(items[0].data(Qt.UserRole) or 0)

    def _update_create_enabled(self):
        self.create_btn.setEnabled(bool(self._selected_package_id()))

    # ---------- 下单 ----------

    def _create_order(self):
        pkg_id = self._selected_package_id()
        if not pkg_id:
            self.status.setText("请先选择套餐")
            return
        channel = self.channel_box.currentData() or "default"
        self.create_btn.setEnabled(False)
        self.status.setText("正在创建订单…")
        b = self._b

        def _fetch():
            try:
                b.order.emit(self.api.create_order(pkg_id, channel))
            except Exception as e:  # noqa: BLE001
                b.error.emit(str(e))

        threading.Thread(target=_fetch, daemon=True).start()

    def _on_order(self, data):
        self.create_btn.setEnabled(True)
        data = data or {}
        order_no = data.get("orderNo") or data.get("outTradeNo") or data.get("orderId") or ""
        pay_url = data.get("codeUrl") or data.get("qrCode") or data.get("payUrl") \
            or data.get("paymentUrl") or data.get("code_url") or ""
        amount = data.get("amount", "")
        lines = ["订单创建成功"]
        if order_no:
            lines.append(f"订单号：{order_no}")
        if amount != "":
            lines.append(f"金额：￥{amount}")
        if pay_url:
            lines.append(f"支付链接：{pay_url}")
        else:
            lines.append("二维码占位：[ 请使用手机扫码支付 ]")
        lines.append("")
        lines.append("支付完成后返回本窗口，余额将自动更新（若无更新请点击刷新）。")
        self.order_view.setPlainText("\n".join(lines))
        self.status.setText("请完成支付")
        self.credits_changed.emit()
